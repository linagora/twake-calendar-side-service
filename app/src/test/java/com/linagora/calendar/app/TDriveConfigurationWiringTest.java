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

package com.linagora.calendar.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.apache.james.core.Username;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.apache.james.utils.PropertiesProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Stage;
import com.google.inject.util.Modules;
import com.linagora.calendar.restapi.routes.ImportAttachmentOffloader;
import com.linagora.calendar.restapi.routes.ImportIcsRewriter;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveModule;
import com.linagora.calendar.saas.drive.tdrive.ClouderyTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.LocalPartTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStore;
import com.linagora.calendar.saas.drive.tdrive.TDriveInstanceResolver;
import com.linagora.calendar.storage.FileUploadConfiguration;

class TDriveConfigurationWiringTest {
    @TempDir
    Path directory;

    private TwakeCalendarConfiguration configuration(String properties) throws IOException {
        Path configurationDirectory = Files.createDirectories(directory.resolve("conf"));
        Files.writeString(configurationDirectory.resolve("configuration.properties"), properties);

        return TwakeCalendarConfiguration.builder()
            .workingDirectory(directory.toFile())
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY)
            .autoCompleteChoice(TwakeCalendarConfiguration.AutoCompleteChoice.MEMORY)
            .calendarEventSearchChoice(TwakeCalendarConfiguration.CalendarEventSearchChoice.MEMORY)
            .build();
    }

    private Injector injector(TwakeCalendarConfiguration configuration) {
        PropertiesProvider propertiesProvider = new PropertiesProvider(
            new FileSystemImpl(configuration.directories()),
            configuration.configurationPath());

        return Guice.createInjector(Stage.PRODUCTION,
            TwakeCalendarMain.chooseTDrive(configuration.tdriveEnabled()),
            binder -> {
                binder.bind(PropertiesProvider.class).toInstance(propertiesProvider);
                binder.bind(Authenticator.class).toInstance(mock(Authenticator.class));
                binder.bind(MetricFactory.class).toInstance(new RecordingMetricFactory());
                binder.bind(FileUploadConfiguration.class).toInstance(FileUploadConfiguration.DEFAULT);
            });
    }

    @Test
    void shouldLeaveDriveDisabledByDefaultWithoutBindingOrConstructingClient() throws IOException {
        TwakeCalendarConfiguration application = configuration("");

        Injector injector = Guice.createInjector(Stage.PRODUCTION,
            TwakeCalendarMain.chooseTDrive(application.tdriveEnabled()));

        assertThat(application.tdriveEnabled()).isFalse();
        assertThat(injector.getExistingBinding(Key.get(AttachmentStore.class))).isNull();
        assertThat(injector.getExistingBinding(Key.get(TDriveConfiguration.class))).isNull();
        assertThat(injector.getExistingBinding(Key.get(TDriveAttachmentStore.class))).isNull();
        assertThat(injector.getExistingBinding(Key.get(ImportIcsRewriter.class))).isNull();
    }

    @Test
    void shouldReadDriveConfigurationInTheEnabledModuleAndBindSingleton() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.instance.resolver=local-part
            twp.drive.local-part.domain=example.com
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.issuer.token=example-partner-token
            twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
            """);

        Injector injector = injector(application);
        TDriveConfiguration driveConfiguration = injector.getInstance(TDriveConfiguration.class);
        AttachmentStore client = injector.getInstance(AttachmentStore.class);

        assertThat(application.tdriveEnabled()).isTrue();
        assertThat(injector.getInstance(ImportIcsRewriter.class)).isInstanceOf(ImportAttachmentOffloader.class);
        assertThat(driveConfiguration.localPartDomain()).contains("example.com");
        TDriveInstanceResolver resolver = injector.getInstance(TDriveInstanceResolver.class);
        assertThat(resolver).isInstanceOf(LocalPartTDriveInstanceResolver.class);
        assertThat(resolver.resolve(Username.of("AliceSmith@mail.example.net")).block()).isEqualTo("alicesmith.example.com");
        assertThat(client)
            .isInstanceOf(TDriveAttachmentStore.class)
            .isSameAs(injector.getInstance(AttachmentStore.class));
    }

    @Test
    void shouldResolveFromEmailWhenLocalPartDomainIsNotConfigured() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.instance.resolver=local-part
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.issuer.token=example-issuer-token
            twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
            """);

        Injector injector = injector(application);
        assertThat(injector.getInstance(TDriveConfiguration.class).localPartDomain()).isEmpty();
        assertThat(injector.getInstance(TDriveInstanceResolver.class).resolve(Username.of("AliceSmith@mail.example.net")).block())
            .isEqualTo("alicesmith.mail.example.net");
    }

    @Test
    void shouldSelectClouderyResolverByDefault() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.issuer.token=example-public-token
            twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
            twp.drive.lookup.url=https://manager.example.com/api/v2/instances
            twp.drive.lookup.token=example-lookup-token
            """);

        Injector injector = injector(application);

        assertThat(injector.getInstance(TDriveInstanceResolver.class))
            .isInstanceOf(ClouderyTDriveInstanceResolver.class)
            .isSameAs(injector.getInstance(TDriveInstanceResolver.class));
        assertThat(injector.getInstance(AttachmentStore.class)).isInstanceOf(TDriveAttachmentStore.class);
    }

    @Test
    void shouldAllowGuiceBindingToOverrideResolver() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.issuer.token=example-public-token
            twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
            twp.drive.instance.resolver=cloudery
            twp.drive.lookup.url=https://manager.example.com/api/v2/instances
            twp.drive.lookup.token=example-lookup-token
            """);
        TDriveConfiguration drive = injector(application).getInstance(TDriveConfiguration.class);
        TDriveInstanceResolver override = new LocalPartTDriveInstanceResolver(Optional.of("example.com"));

        Injector injector = Guice.createInjector(Stage.PRODUCTION, Modules.override(new TDriveModule())
            .with(binder -> {
                binder.bind(TDriveConfiguration.class).toInstance(drive);
                binder.bind(TDriveInstanceResolver.class).toInstance(override);
            }));

        assertThat(injector.getInstance(TDriveInstanceResolver.class)).isSameAs(override);
        assertThat(injector.getInstance(AttachmentStore.class)).isInstanceOf(TDriveAttachmentStore.class);
    }

    @Test
    void shouldRejectLdapResolverWithoutLdapUserBackend() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.issuer.token=example-public-token
            twp.drive.public.url.template=https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/
            twp.drive.instance.resolver=ldap
            """);

        assertThatThrownBy(() -> injector(application))
            .hasMessageContaining("LDAP Drive resolver requires LDAP user configuration");
    }

    @Test
    void shouldIgnoreInvalidDrivePropertiesWhenDisabled() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=false
            twp.drive.local-part.domain=invalid
            twp.drive.timeout=invalid
            """);

        Injector injector = injector(application);

        assertThat(injector.getExistingBinding(Key.get(AttachmentStore.class))).isNull();
    }

    @Test
    void shouldRejectIncompleteEnabledDriveConfigurationDuringInjection() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            """);

        assertThatThrownBy(() -> injector(application))
            .hasMessageContaining("twp.drive.token.url.template");
    }
}
