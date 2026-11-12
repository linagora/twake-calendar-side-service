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
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.inject.Guice;
import com.google.inject.util.Modules;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
import com.linagora.calendar.saas.drive.api.AttachmentUri;
import com.linagora.calendar.saas.drive.configuration.ClouderyLookup;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration.InstanceResolver;
import com.linagora.calendar.saas.drive.configuration.TDriveModule;
import com.linagora.calendar.saas.drive.tdrive.LocalPartTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStore;
import com.linagora.calendar.saas.drive.tdrive.TDriveInstanceResolver;

import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;

class TDriveAttachmentStoreTest implements AttachmentStoreContract {
    private static final String FQDN = "alice.example.com";
    private static final String LOOKUP_PATH = "/api/v2/instances";
    private static final String LOOKUP_TOKEN = "secret-lookup-token";
    private static final String TOKEN_PATH = "/api/public/instances/" + FQDN + "/token";
    private static final String FOLDER_PATH = "/alice.example.com/files/metadata";
    private static final String CREATE_PATH = "/alice.example.com/files/io.cozy.files.root-dir";
    private static final String UPLOAD_PATH = "/alice.example.com/files/folder-id";
    private static final String PERMISSION_PATH = "/alice.example.com/permissions";
    private static final String PARTNER_TOKEN = "secret-partner-token";
    private static final String SERVICE_TOKEN = "secret-service-token";
    private static final String TOKEN_JSON = """
        {"token": "{serviceToken}"}
        """.replace("{serviceToken}", SERVICE_TOKEN);
    private static final String FOLDER_JSON = """
        {
          "data": {
            "id": "folder-id",
            "attributes": {"type": "directory"}
          }
        }
        """;
    private static final String FILE_JSON = """
        {
          "data": {
            "id": "file-id",
            "attributes": {"type": "file"}
          }
        }
        """;
    private static final String PERMISSION_JSON = """
        {
          "data": {
            "attributes": {
              "shortcodes": {"calendar": "a+b/c?&=#"}
            }
          }
        }
        """;

    private enum TlsMode { VERIFY_CERTIFICATE, TRUST_SELF_SIGNED }

    @RegisterExtension
    static WireMockExtension broker = WireMockExtension.newInstance()
        .options(WireMockConfiguration.options().dynamicPort().dynamicHttpsPort())
        .failOnUnmatchedRequests(false)
        .build();
    @RegisterExtension
    static WireMockExtension drive = WireMockExtension.newInstance()
        .options(WireMockConfiguration.options().dynamicPort())
        .failOnUnmatchedRequests(false)
        .build();

    @BeforeEach
    void setUp() {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withBody(TOKEN_JSON)));
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .willReturn(aResponse().withBody(FOLDER_JSON)));
        drive.stubFor(post(urlPathEqualTo(CREATE_PATH))
            .willReturn(aResponse().withStatus(201).withBody(FOLDER_JSON)));
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .willReturn(aResponse().withStatus(201).withBody(FILE_JSON)));
        drive.stubFor(post(urlPathEqualTo(PERMISSION_PATH))
            .willReturn(aResponse().withBody(PERMISSION_JSON)));
    }

    @Override
    public AttachmentStore client() {
        return localPartClient(configuration(Duration.ofSeconds(3)));
    }

    private TDriveAttachmentStore localPartClient(TDriveConfiguration configuration) {
        return store(configuration, new LocalPartTDriveInstanceResolver(configuration.localPartDomain()));
    }

    private AttachmentStore clouderyClient() {
        URI brokerUrl = URI.create(broker.getRuntimeInfo().getHttpBaseUrl());
        var lookup = new ClouderyLookup(URI.create(brokerUrl + LOOKUP_PATH), LOOKUP_TOKEN);
        TDriveConfiguration configuration = configuration(brokerUrl, Duration.ofSeconds(3), TlsMode.VERIFY_CERTIFICATE, Optional.of(lookup));
        TDriveInstanceResolver resolver = Guice.createInjector(Modules.override(new TDriveModule())
                .with(binder -> binder.bind(TDriveConfiguration.class).toInstance(configuration)))
            .getInstance(TDriveInstanceResolver.class);
        return store(configuration, resolver);
    }

    private TDriveAttachmentStore store(TDriveConfiguration configuration, TDriveInstanceResolver resolver) {
        return new TDriveAttachmentStore(configuration, resolver, uri -> URI.create(drive.baseUrl() + "/" + uri.getHost()));
    }

    private TDriveConfiguration configuration(URI brokerUrl, Duration timeout, TlsMode tls) {
        return configuration(brokerUrl, timeout, tls, Optional.empty());
    }

    private TDriveConfiguration configuration(URI brokerUrl, Duration timeout, TlsMode tls, Optional<ClouderyLookup> lookup) {
        return new TDriveConfiguration(true, lookup.isPresent() ? Optional.empty() : Optional.of("example.com"),
            brokerUrl + "/api/public/instances/{fqdn}/token",
            PARTNER_TOKEN,
            "Calendar attachments",
            "https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/",
            timeout, tls == TlsMode.TRUST_SELF_SIGNED, lookup, lookup.isPresent() ? InstanceResolver.CLOUDERY : InstanceResolver.LOCAL_PART);
    }

    private TDriveConfiguration configuration(Duration timeout) {
        return configuration(URI.create(broker.getRuntimeInfo().getHttpBaseUrl()), timeout, TlsMode.VERIFY_CERTIFICATE);
    }

    @Override
    public URI expectedUri() {
        return URI.create("https://alice-drive.example.com/public?sharecode=a%2Bb%2Fc%3F%26%3D%23#/");
    }

    @Override
    public byte[] storedContent() {
        return drive.findAll(postRequestedFor(urlPathEqualTo(UPLOAD_PATH))).getFirst().getBody();
    }

    @Override
    public int requestCount() {
        return broker.getAllServeEvents().size() + drive.getAllServeEvents().size();
    }

    @Override
    public void failStorage() {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withStatus(503)));
    }

    @Test
    void shouldSendExactHttpContractAndKeepCredentialsOnTheirRespectiveHosts() {
        client().upload(ATTACHMENT, USER).block();

        broker.verify(postRequestedFor(urlPathEqualTo(TOKEN_PATH))
            .withHeader("Authorization", equalTo("Bearer " + PARTNER_TOKEN))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(equalToJson("""
                {
                  "audience": "cli",
                  "scope": "io.cozy.files io.cozy.permissions"
                }
                """)));

        drive.verify(getRequestedFor(urlPathEqualTo(FOLDER_PATH))
            .withQueryParam("Path", equalTo("/Calendar attachments")));
        drive.verify(0, postRequestedFor(urlPathEqualTo(CREATE_PATH)));

        drive.verify(postRequestedFor(urlPathEqualTo(UPLOAD_PATH))
            .withQueryParam("Type", equalTo("file"))
            .withQueryParam("Name", equalTo(ATTACHMENT.fileName()))
            .withHeader("Content-Type", equalTo("application/pdf"))
            .withHeader("Content-MD5", equalTo(Base64.getEncoder().encodeToString(DigestUtils.md5(CONTENT)))));

        drive.verify(postRequestedFor(urlPathEqualTo(PERMISSION_PATH))
            .withQueryParam("codes", equalTo("calendar"))
            .withHeader("Content-Type", equalTo("application/vnd.api+json")));
        String permissionRequestBody = drive.findAll(postRequestedFor(urlPathEqualTo(PERMISSION_PATH)))
            .getFirst().getBodyAsString();
        assertThatJson(permissionRequestBody).isEqualTo("""
            {
              "data": {
                "type": "io.cozy.permissions",
                "attributes": {
                  "permissions": {
                    "files": {
                      "type": "io.cozy.files",
                      "verbs": ["GET"],
                      "values": ["file-id"]
                    }
                  }
                }
              }
            }
            """);

        assertThat(drive.getAllServeEvents()).allSatisfy(event -> {
            assertThat(event.getRequest().getHeader("Authorization")).isEqualTo("Bearer " + SERVICE_TOKEN);
            assertThat(event.getRequest().getHeader("Accept")).isEqualTo("application/vnd.api+json");
        });
        assertThat(requestCount()).isEqualTo(4);
    }

    @Test
    void shouldUseClouderyHostAndKeepLookupAndTokenCredentialsSeparate() {
        String email = "alice.smith+calendar@other.example";
        broker.stubFor(get(urlPathEqualTo(LOOKUP_PATH))
            .withQueryParam("email", equalTo(email))
            .withQueryParam("limit", equalTo("2"))
            .withHeader("Authorization", equalTo("Bearer " + LOOKUP_TOKEN))
            .willReturn(aResponse().withBody("""
                {"count": 1, "items": [{"fqdn": "alice.example.com", "internal_email": "<email>"}]}
                """.replace("<email>", email))));

        assertThat(clouderyClient().upload(ATTACHMENT, Username.of(email)).block().value()).isEqualTo(expectedUri());

        broker.verify(postRequestedFor(urlPathEqualTo(TOKEN_PATH))
            .withHeader("Authorization", equalTo("Bearer " + PARTNER_TOKEN)));
        drive.verify(postRequestedFor(urlPathEqualTo(UPLOAD_PATH))
            .withHeader("Authorization", equalTo("Bearer " + SERVICE_TOKEN)));
        assertThat(requestCount()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://alice.example.com", "alice.example.com/path", "localhost", "127.0.0.1"})
    void shouldRejectInvalidLookupHostnameBeforeMintingToken(String fqdn) {
        broker.stubFor(get(urlPathEqualTo(LOOKUP_PATH))
            .willReturn(aResponse().withBody("""
                {"count": 1, "items": [{"fqdn": "<fqdn>", "internal_email": "alice@example.com"}]}
                """.replace("<fqdn>", fqdn))));

        assertThatThrownBy(() -> clouderyClient().upload(ATTACHMENT, Username.of("alice@example.com")).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_ATTACHMENT));
        assertThat(requestCount()).isEqualTo(1);
    }

    static Stream<Username> normalizableUsernames() {
        return Stream.of("ALICE@OTHER.COM", "alice", "Alice@other.com").map(Username::of);
    }

    static Stream<Username> localPartsSharingASlug() {
        return Stream.of("a.lice@example.com", "a_lice@example.com", "alice+calendar@example.com",
            "alice-@example.com", "A.L_I+C!E-@other.com").map(Username::of);
    }

    @ParameterizedTest
    @MethodSource("localPartsSharingASlug")
    void shouldRejectLocalPartThatTheSlugRuleWouldChange(Username username) {
        assertThatThrownBy(() -> client().upload(ATTACHMENT, username).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_ATTACHMENT));
        assertThat(requestCount()).isZero();
    }

    @ParameterizedTest
    @MethodSource("normalizableUsernames")
    void shouldUseLocalPartAndConfiguredDomainWithoutLookup(Username username) {
        assertThat(client().upload(ATTACHMENT, username).block().value()).isEqualTo(expectedUri());
        assertThat(broker.getAllServeEvents()).hasSize(1);
        broker.verify(postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
    }

    @Test
    void shouldNormalizeSlugWithCustomInstanceDomain() {
        String fqdn = "alice-smith-2.stg.example.org";
        String tokenPath = "/api/public/instances/" + fqdn + "/token";
        broker.stubFor(post(urlPathEqualTo(tokenPath))
            .willReturn(aResponse().withBody(TOKEN_JSON)));
        drive.stubFor(get(urlPathEqualTo("/" + fqdn + "/files/metadata"))
            .willReturn(aResponse().withBody(FOLDER_JSON)));
        drive.stubFor(post(urlPathEqualTo("/" + fqdn + "/files/folder-id"))
            .willReturn(aResponse().withStatus(201).withBody(FILE_JSON)));
        drive.stubFor(post(urlPathEqualTo("/" + fqdn + "/permissions"))
            .willReturn(aResponse().withBody(PERMISSION_JSON)));
        TDriveConfiguration configuration = new TDriveConfiguration(true, Optional.of("Stg.Example.Org"),
            broker.getRuntimeInfo().getHttpBaseUrl() + "/api/public/instances/{fqdn}/token",
            PARTNER_TOKEN,
            "Calendar attachments",
            "https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/",
            Duration.ofSeconds(3), false, Optional.empty(), InstanceResolver.LOCAL_PART);

        AttachmentUri uri = localPartClient(configuration)
            .upload(ATTACHMENT, Username.of("Alice-Smith-2@company.com"))
            .block();

        assertThat(uri.value())
            .isEqualTo(URI.create("https://alice-smith-2-drive.stg.example.org/public?sharecode=a%2Bb%2Fc%3F%26%3D%23#/"));
        broker.verify(postRequestedFor(urlPathEqualTo(tokenPath)));
        assertThat(requestCount()).isEqualTo(4);
    }

    static Stream<Username> usernamesWithoutValidHostname() {
        return Stream.of("___", "_-_-", "é", "a".repeat(64)).map(Username::fromLocalPartWithoutDomain);
    }

    @ParameterizedTest
    @MethodSource("usernamesWithoutValidHostname")
    void shouldRejectLocalPartThatCannotFormHostname(Username username) {
        assertThatThrownBy(() -> client().upload(ATTACHMENT, username).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_ATTACHMENT));
        assertThat(requestCount()).isZero();
    }

    @Test
    void shouldPreserveContentTypeParametersInUploadHeader() {
        Attachment attachment = new Attachment("notes.txt", ContentType.of("text/plain; charset=UTF-8"), ATTACHMENT.content());

        client().upload(attachment, USER).block();

        drive.verify(postRequestedFor(urlPathEqualTo(UPLOAD_PATH))
            .withHeader("Content-Type", equalTo("text/plain; charset=UTF-8")));
    }

    @Test
    void shouldCreateMissingFolderUnderRoot() {
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .willReturn(aResponse().withStatus(404)));

        client().upload(ATTACHMENT, USER).block();

        drive.verify(postRequestedFor(urlPathEqualTo(CREATE_PATH))
            .withQueryParam("Type", equalTo("directory"))
            .withQueryParam("Name", equalTo("Calendar attachments")));
    }

    @Test
    void shouldRecoverFromConcurrentFolderCreation() {
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .inScenario("folder-race")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404))
            .willSetStateTo("created"));
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .inScenario("folder-race")
            .whenScenarioStateIs("created")
            .willReturn(aResponse().withBody(FOLDER_JSON)));
        drive.stubFor(post(urlPathEqualTo(CREATE_PATH))
            .willReturn(aResponse().withStatus(409)));

        assertThat(client().upload(ATTACHMENT, USER).block().value()).isEqualTo(expectedUri());

        drive.verify(2, getRequestedFor(urlPathEqualTo(FOLDER_PATH)));
        drive.verify(1, postRequestedFor(urlPathEqualTo(CREATE_PATH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        """
        {"token": ""}
        """,
        """
        {"token": "bad token"}
        """,
        "{\"token\": \"bad\\ttoken\"}",
        "{\"token\": \"bad\\r\\ntoken\"}",
        "{\"token\": \"bad\\u007ftoken\"}",
        "{\"token\": \"caf\u00e9\"}",
        "null",
        "broken-json"})
    void shouldRejectMissingOrMalformedServiceToken(String body) {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withBody(body)));

        assertFailure(Reason.INVALID_RESPONSE);

        assertThat(drive.getAllServeEvents()).isEmpty();
    }

    static Stream<HttpResponseStatus> tokenFailureStatuses() {
        return Stream.of(HttpResponseStatus.UNAUTHORIZED, HttpResponseStatus.FORBIDDEN, HttpResponseStatus.NOT_FOUND,
            HttpResponseStatus.TOO_MANY_REQUESTS, HttpResponseStatus.INTERNAL_SERVER_ERROR, HttpResponseStatus.SERVICE_UNAVAILABLE);
    }

    @ParameterizedTest
    @MethodSource("tokenFailureStatuses")
    void shouldSurfaceTokenStatusAndStopFlow(HttpResponseStatus status) {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withStatus(status.code())));

        assertFailure(Reason.UPSTREAM_FAILURE, status);

        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void shouldStopAfterTokenFailureWithoutRetrying() {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withStatus(403)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.FORBIDDEN);

        assertThat(requestCount()).isEqualTo(1);
    }

    @Test
    void shouldStopAfterFolderLookupFailureWithoutRetrying() {
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .willReturn(aResponse().withStatus(403)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.FORBIDDEN);

        assertThat(requestCount()).isEqualTo(2);
    }

    @Test
    void shouldStopAfterUploadFailureWithoutRetrying() {
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .willReturn(aResponse().withStatus(403)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.FORBIDDEN);

        assertThat(requestCount()).isEqualTo(3);
    }

    @Test
    void shouldStopAfterPermissionFailureWithoutRetrying() {
        drive.stubFor(post(urlPathEqualTo(PERMISSION_PATH))
            .willReturn(aResponse().withStatus(403)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.FORBIDDEN);

        assertThat(requestCount()).isEqualTo(4);
    }

    @Test
    void shouldNotUploadWhenFolderCreationFails() {
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .willReturn(aResponse().withStatus(404)));
        drive.stubFor(post(urlPathEqualTo(CREATE_PATH))
            .willReturn(aResponse().withStatus(500)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.INTERNAL_SERVER_ERROR);

        drive.verify(0, postRequestedFor(urlPathEqualTo(UPLOAD_PATH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        """
        {"data": {"id": "file-id"}}
        """,
        """
        {"data": {"id": "", "attributes": {"type": "file"}}}
        """})
    void shouldNotShareAnUnusableUploadResponse(String body) {
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .willReturn(aResponse().withStatus(201).withBody(body)));

        assertFailure(Reason.INVALID_RESPONSE);

        drive.verify(0, postRequestedFor(urlPathEqualTo(PERMISSION_PATH)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        """
        {"data": {}}
        """,
        """
        {"data": {"attributes": {"shortcodes": {"calendar": ""}}}}
        """})
    void shouldNotReturnUriWithoutShareCode(String body) {
        drive.stubFor(post(urlPathEqualTo(PERMISSION_PATH))
            .willReturn(aResponse().withBody(body)));

        assertFailure(Reason.INVALID_RESPONSE);
    }

    @Test
    void shouldNeverExposeUpstreamResponseBodiesInErrors() {
        String encodedContent = Base64.getEncoder().encodeToString(CONTENT);
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withStatus(500)
                .withBody(PARTNER_TOKEN + SERVICE_TOKEN + encodedContent)));

        assertThatThrownBy(() -> client().upload(ATTACHMENT, USER).block())
            .isInstanceOf(AttachmentStorageException.class)
            .hasMessageNotContaining(PARTNER_TOKEN)
            .hasMessageNotContaining(SERVICE_TOKEN)
            .hasMessageNotContaining(encodedContent);
    }

    @Test
    void shouldNotFollowRedirectsWithCredentials() {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withStatus(302)
                .withHeader("Location", drive.baseUrl() + "/unexpected")));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.FOUND);

        assertThat(drive.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldBoundRequestDuration() {
        broker.stubFor(post(urlPathEqualTo(TOKEN_PATH))
            .willReturn(aResponse().withFixedDelay(1000).withBody("""
                {"token": "slow-token"}
                """)));
        AttachmentStore timedClient = localPartClient(configuration(Duration.ofMillis(100)));

        assertThatThrownBy(() -> timedClient.upload(ATTACHMENT, USER).block(Duration.ofSeconds(3)))
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(Reason.TIMEOUT));
        assertThat(drive.getAllServeEvents()).isEmpty();
    }

    @Test
    void shouldRejectSelfSignedCertificatesByDefault() {
        var secureClient = localPartClient(configuration(URI.create(broker.getRuntimeInfo().getHttpsBaseUrl()), Duration.ofSeconds(3), TlsMode.VERIFY_CERTIFICATE));

        assertThatThrownBy(() -> secureClient.upload(ATTACHMENT, USER).block(Duration.ofSeconds(5)))
            .isInstanceOf(AttachmentStorageException.class);
        assertThat(requestCount()).isZero();
    }

    @Test
    void shouldSupportExplicitInsecureTlsForDevelopment() {
        var insecureClient = localPartClient(configuration(URI.create(broker.getRuntimeInfo().getHttpsBaseUrl()), Duration.ofSeconds(3), TlsMode.TRUST_SELF_SIGNED));

        assertThat(insecureClient.upload(ATTACHMENT, USER).block(Duration.ofSeconds(5)).value()).isEqualTo(expectedUri());
    }

    @Test
    void shouldKeepConcurrentUsersInTheirOwnInstanceAndUseTheirOwnTokens() {
        broker.stubFor(post(urlPathEqualTo("/api/public/instances/bob.example.com/token"))
            .willReturn(aResponse().withBody(TOKEN_JSON.replace(SERVICE_TOKEN, "bob-service-token"))));
        drive.stubFor(get(urlPathEqualTo("/bob.example.com/files/metadata"))
            .willReturn(aResponse().withBody(FOLDER_JSON.replace("folder-id", "bob-folder-id"))));
        drive.stubFor(post(urlPathEqualTo("/bob.example.com/files/bob-folder-id"))
            .willReturn(aResponse().withBody(FILE_JSON.replace("file-id", "bob-file-id"))));
        drive.stubFor(post(urlPathEqualTo("/bob.example.com/permissions"))
            .willReturn(aResponse().withBody(PERMISSION_JSON)));
        AttachmentStore sharedClient = client();

        var uris = Mono.zip(sharedClient.upload(ATTACHMENT, USER), sharedClient.upload(ATTACHMENT, Username.of("bob@example.com")))
            .block(Duration.ofSeconds(5));

        assertThat(uris.getT1().value()).isEqualTo(expectedUri());
        assertThat(uris.getT2().value())
            .isEqualTo(URI.create("https://bob-drive.example.com/public?sharecode=a%2Bb%2Fc%3F%26%3D%23#/"));
        assertThat(drive.getAllServeEvents()).allSatisfy(event -> {
            String expectedToken = event.getRequest().getUrl().startsWith("/bob.example.com/")
                ? "bob-service-token"
                : SERVICE_TOKEN;
            assertThat(event.getRequest().getHeader("Authorization")).isEqualTo("Bearer " + expectedToken);
        });
        String bobPermissionRequestBody = drive.findAll(postRequestedFor(urlPathEqualTo("/bob.example.com/permissions")))
            .getFirst().getBodyAsString();
        assertThatJson(bobPermissionRequestBody)
            .inPath("data.attributes.permissions.files.values").isEqualTo("""
                ["bob-file-id"]
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{}",
        """
        {"data": {"id": "existing-file", "attributes": {"type": "file"}}}
        """})
    void shouldRejectUnusableFolderWithoutUploading(String body) {
        drive.stubFor(get(urlPathEqualTo(FOLDER_PATH))
            .willReturn(aResponse().withBody(body)));

        assertFailure(Reason.INVALID_RESPONSE);

        drive.verify(0, postRequestedFor(urlPathEqualTo(CREATE_PATH)));
        drive.verify(0, postRequestedFor(urlPathEqualTo(UPLOAD_PATH)));
    }

    @Test
    void shouldRetryAnUploadConflictWithAUniqueNameBeforeSharing() {
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .withQueryParam("Name", equalTo(ATTACHMENT.fileName()))
            .willReturn(aResponse().withStatus(409)));

        assertThat(client().upload(ATTACHMENT, USER).block().value()).isEqualTo(expectedUri());

        drive.verify(2, postRequestedFor(urlPathEqualTo(UPLOAD_PATH)));
        var retry = drive.findAll(postRequestedFor(urlPathEqualTo(UPLOAD_PATH))
            .withQueryParam("Name", matching("résumé #1 & notes-[0-9a-f-]{36}\\.pdf")));
        assertThat(retry).singleElement().satisfies(request -> {
            assertThat(request.getBody()).isEqualTo(CONTENT);
            assertThat(request.getHeader("Content-MD5"))
                .isEqualTo(Base64.getEncoder().encodeToString(DigestUtils.md5(CONTENT)));
        });
        broker.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH)));
        drive.verify(1, getRequestedFor(urlPathEqualTo(FOLDER_PATH)));
        drive.verify(1, postRequestedFor(urlPathEqualTo(PERMISSION_PATH)));
    }

    @Test
    void shouldKeepALongConflictNameWithinTheUtf8ByteLimit() {
        String stem = "📄".repeat(62);
        String fileName = stem + ".pdf";
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .withQueryParam("Name", equalTo(fileName))
            .willReturn(aResponse().withStatus(409)));

        var attachment = new Attachment(fileName, ATTACHMENT.contentType(), CONTENT);
        assertThat(client().upload(attachment, USER).block().value()).isEqualTo(expectedUri());

        drive.verify(1, postRequestedFor(urlPathEqualTo(UPLOAD_PATH))
            .withQueryParam("Name", matching("📄".repeat(53) + "-[0-9a-f-]{36}\\.pdf")));
    }

    @Test
    void shouldReportASecondUploadConflictWithoutRetryingOrSharing() {
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .willReturn(aResponse().withStatus(409)));

        assertFailure(Reason.UPSTREAM_FAILURE, HttpResponseStatus.CONFLICT);

        drive.verify(2, postRequestedFor(urlPathEqualTo(UPLOAD_PATH)));
        drive.verify(0, postRequestedFor(urlPathEqualTo(PERMISSION_PATH)));
    }

    @Test
    void shouldNotRetryAnUploadWhenTheConnectionDrops() {
        drive.stubFor(post(urlPathEqualTo(UPLOAD_PATH))
            .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        assertFailure(Reason.UPSTREAM_FAILURE);

        drive.verify(1, postRequestedFor(urlPathEqualTo(UPLOAD_PATH)));
        drive.verify(0, postRequestedFor(urlPathEqualTo(PERMISSION_PATH)));
    }

    private void assertFailure(Reason reason) {
        assertFailure(reason, Optional.empty());
    }

    private void assertFailure(Reason reason, HttpResponseStatus status) {
        assertFailure(reason, Optional.of(status));
    }

    private void assertFailure(Reason reason, Optional<HttpResponseStatus> status) {
        assertThatThrownBy(() -> client().upload(ATTACHMENT, USER).block(Duration.ofSeconds(5)))
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> {
                assertThat(error.reason()).isEqualTo(reason);
                assertThat(error.status()).isEqualTo(status.map(HttpResponseStatus::code).orElse(-1));
            });
    }
}
