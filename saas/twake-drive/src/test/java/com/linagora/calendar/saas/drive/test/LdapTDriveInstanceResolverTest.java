/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.saas.drive.test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.apache.commons.configuration2.plist.PropertyListConfiguration;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.google.inject.Guice;
import com.google.inject.util.Modules;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration.InstanceResolver;
import com.linagora.calendar.saas.drive.configuration.TDriveModule;
import com.linagora.calendar.saas.drive.tdrive.LdapTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveInstanceResolver;
import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.sdk.Entry;
import com.unboundid.ldap.sdk.LDAPConnectionPool;

class LdapTDriveInstanceResolverTest {
    private static final String BASE_DN = "dc=example,dc=com";
    private static final String USER_BASE = "ou=people," + BASE_DN;
    private static final Username USER = Username.of("alice.smith@example.com");
    private static final String FQDN = "assigned-instance.example.com";
    private static final Attachment ATTACHMENT = new Attachment("test.txt", ContentType.of("text/plain"), new byte[] {97});

    @RegisterExtension
    static WireMockExtension drive = WireMockExtension.newInstance()
        .options(WireMockConfiguration.options().dynamicPort())
        .build();

    private InMemoryDirectoryServer ldap;
    private LDAPConnectionPool pool;
    private LdapRepositoryConfiguration ldapConfiguration;

    @BeforeEach
    void setUp() throws Exception {
        InMemoryDirectoryServerConfig serverConfiguration = new InMemoryDirectoryServerConfig(BASE_DN);
        serverConfiguration.setSchema(null);
        ldap = new InMemoryDirectoryServer(serverConfiguration);
        ldap.startListening();
        ldap.add(new Entry("dn: " + BASE_DN, "objectClass: domain", "dc: example"));
        ldap.add(new Entry("dn: " + USER_BASE, "objectClass: organizationalUnit", "ou: people"));
        pool = new LDAPConnectionPool(ldap.getConnection(), 1, 2);
        PropertyListConfiguration properties = new PropertyListConfiguration();
        properties.addProperty("[@ldapHost]", "ldap://127.0.0.1:" + ldap.getListenPort());
        properties.addProperty("[@userBase]", USER_BASE);
        properties.addProperty("[@userObjectClass]", "inetOrgPerson");
        properties.addProperty("[@userIdAttribute]", "mail");
        properties.addProperty("[@connectionTimeout]", "2000");
        properties.addProperty("[@readTimeout]", "2000");
        ldapConfiguration = LdapRepositoryConfiguration.from(properties);

        drive.stubFor(post(urlPathEqualTo("/instances/" + FQDN + "/token"))
            .willReturn(aResponse().withBody("""
                {"token": "service-token"}
                """)));
        drive.stubFor(get(urlPathEqualTo("/" + FQDN + "/files/metadata"))
            .willReturn(aResponse().withBody("""
                {"data": {"id": "folder-id", "attributes": {"type": "directory"}}}
                """)));
        drive.stubFor(post(urlPathEqualTo("/" + FQDN + "/files/folder-id"))
            .willReturn(aResponse().withStatus(201).withBody("""
                {"data": {"id": "file-id", "attributes": {"type": "file"}}}
                """)));
        drive.stubFor(post(urlPathEqualTo("/" + FQDN + "/permissions"))
            .willReturn(aResponse().withBody("""
                {"data": {"attributes": {"shortcodes": {"calendar": "public-code"}}}}
                """)));
    }

    @AfterEach
    void tearDown() {
        if (pool != null) {
            pool.close();
        }
        if (ldap != null) {
            ldap.shutDown(true);
        }
    }

    private void addUser(String uid, String email, String... hosts) throws Exception {
        Entry entry = new Entry("uid=" + uid + "," + USER_BASE);
        entry.addAttribute("objectClass", "inetOrgPerson");
        entry.addAttribute("uid", uid);
        entry.addAttribute("mail", email);
        if (hosts.length > 0) {
            entry.addAttribute("twakeWorkspaceUrl", hosts);
        }
        ldap.add(entry);
    }

    private AttachmentStorageClient client() {
        TDriveConfiguration configuration = new TDriveConfiguration(true, "example.com",
            drive.baseUrl() + "/instances/{fqdn}/token", drive.baseUrl() + "/{fqdn}", "public-token",
            "Calendar attachments", "https://{driveFqdn}/public?sharecode={sharecode}#/", Duration.ofSeconds(3), false,
            Optional.empty(), InstanceResolver.LDAP);
        var injector = Guice.createInjector(Modules.override(new TDriveModule()).with(binder -> {
            binder.bind(TDriveConfiguration.class).toInstance(configuration);
            binder.bind(LdapRepositoryConfiguration.class).toInstance(ldapConfiguration);
            binder.bind(LDAPConnectionPool.class).toInstance(pool);
        }));
        assertThat(injector.getInstance(TDriveInstanceResolver.class)).isInstanceOf(LdapTDriveInstanceResolver.class);
        return injector.getInstance(AttachmentStorageClient.class);
    }

    @Test
    void shouldUploadToStoredHostnameThroughGuice() throws Exception {
        addUser("alice", USER.asString(), FQDN);

        URI uri = client().upload(ATTACHMENT, USER).block().value();

        assertThat(uri).hasToString("https://assigned-instance-drive.example.com/public?sharecode=public-code#/");
        drive.verify(postRequestedFor(urlPathEqualTo("/instances/" + FQDN + "/token"))
            .withHeader("Authorization", equalTo("Bearer public-token")));
        assertThat(drive.getAllServeEvents()).hasSize(4);
    }

    @Test
    void shouldSupportDirectGuiceBinding() throws Exception {
        addUser("alice", USER.asString(), FQDN);
        var injector = Guice.createInjector(binder -> {
            binder.bind(LdapRepositoryConfiguration.class).toInstance(ldapConfiguration);
            binder.bind(LDAPConnectionPool.class).toInstance(pool);
            binder.bind(TDriveInstanceResolver.class).to(LdapTDriveInstanceResolver.class);
        });

        assertThat(injector.getInstance(TDriveInstanceResolver.class).resolve(USER).block()).isEqualTo(FQDN);
    }

    @Test
    void shouldKeepDottedAndDifferentDomainUsersSeparate() throws Exception {
        addUser("alice", USER.asString(), FQDN);
        addUser("other-local", "alicesmith@example.com", "other-local.example.com");
        addUser("other-domain", "alice.smith@other.example", "other-domain.example.com");
        var resolver = new LdapTDriveInstanceResolver(ldapConfiguration, pool);

        assertThat(resolver.resolve(USER).block()).isEqualTo(FQDN);
        assertThat(resolver.resolve(Username.of("alicesmith@example.com")).block()).isEqualTo("other-local.example.com");
        assertThat(resolver.resolve(Username.of("alice.smith@other.example")).block()).isEqualTo("other-domain.example.com");
    }

    @Test
    void shouldRejectMissingUserBeforeTokenIssuance() {
        assertInvalidUpload(USER);
    }

    @Test
    void shouldRejectMissingHostnameBeforeTokenIssuance() throws Exception {
        addUser("alice", USER.asString());
        assertInvalidUpload(USER);
    }

    @Test
    void shouldRejectMultipleHostnamesBeforeTokenIssuance() throws Exception {
        addUser("alice", USER.asString(), FQDN, "another.example.com");
        assertInvalidUpload(USER);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3})
    void shouldRejectDuplicateUsersBeforeTokenIssuance(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            addUser("alice" + i, USER.asString(), FQDN);
        }
        assertInvalidUpload(USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "https://assigned-instance.example.com", "victim.example.org", "example.com", "assigned-instance.example.com/evil"})
    void shouldRejectInvalidOrExternalHostnameBeforeTokenIssuance(String hostname) throws Exception {
        addUser("alice", USER.asString(), hostname);
        assertInvalidUpload(USER);
    }

    @Test
    void shouldRejectLocalPartWithoutEmail() {
        assertInvalidUpload(Username.fromLocalPartWithoutDomain("alice"));
    }

    @Test
    void shouldTreatLdapMetacharactersAsLiteralEmail() throws Exception {
        addUser("alice", USER.asString(), FQDN);
        assertInvalidUpload(Username.of("*)(mail=*)@example.com"));
    }

    @Test
    void shouldPropagateLdapFailureBeforeTokenIssuance() {
        pool.close();

        assertThatThrownBy(() -> client().upload(ATTACHMENT, USER).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(Reason.UPSTREAM_FAILURE));
        assertThat(drive.getAllServeEvents()).isEmpty();
    }

    private void assertInvalidUpload(Username user) {
        assertThatThrownBy(() -> client().upload(ATTACHMENT, user).block())
            .isInstanceOf(AttachmentStorageException.class);
        assertThat(drive.getAllServeEvents()).isEmpty();
    }
}
