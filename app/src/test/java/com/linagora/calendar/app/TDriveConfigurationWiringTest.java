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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            binder -> binder.bind(PropertiesProvider.class).toInstance(propertiesProvider));
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
            twp.drive.instance.domain=example.com
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.partner.token=example-partner-token
            twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/
            """);

        Injector injector = injector(application);
        TDriveConfiguration driveConfiguration = injector.getInstance(TDriveConfiguration.class);
        AttachmentStore client = injector.getInstance(AttachmentStore.class);

        assertThat(application.tdriveEnabled()).isTrue();
        assertThat(injector.getInstance(ImportIcsRewriter.class)).isInstanceOf(ImportAttachmentOffloader.class);
        assertThat(driveConfiguration.instanceDomain()).hasToString("example.com");
        assertThat(injector.getInstance(TDriveInstanceResolver.class)).isInstanceOf(LocalPartTDriveInstanceResolver.class);
        assertThat(client)
            .isInstanceOf(TDriveAttachmentStore.class)
            .isSameAs(injector.getInstance(AttachmentStore.class));
    }

    @Test
    void shouldSelectClouderyResolverFromConfiguration() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=true
            twp.drive.instance.domain=example.com
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.partner.token=example-public-token
            twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/
            twp.drive.instance.resolver=cloudery
            twp.drive.lookup.url=https://manager.example.com/api/v2/instances
            twp.drive.lookup.partner.token=example-lookup-token
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
            twp.drive.instance.domain=example.com
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.partner.token=example-public-token
            twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/
            twp.drive.instance.resolver=cloudery
            twp.drive.lookup.url=https://manager.example.com/api/v2/instances
            twp.drive.lookup.partner.token=example-lookup-token
            """);
        TDriveConfiguration drive = injector(application).getInstance(TDriveConfiguration.class);
        TDriveInstanceResolver override = new LocalPartTDriveInstanceResolver(drive.instanceDomain());

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
            twp.drive.instance.domain=example.com
            twp.drive.token.url.template=https://manager.example.com/api/public/instances/{fqdn}/token
            twp.drive.partner.token=example-public-token
            twp.drive.public.url.template=https://{driveFqdn}/public?sharecode={sharecode}#/
            twp.drive.instance.resolver=ldap
            """);

        assertThatThrownBy(() -> injector(application))
            .hasMessageContaining("LDAP Drive resolver requires LDAP user configuration");
    }

    @Test
    void shouldIgnoreInvalidDrivePropertiesWhenDisabled() throws IOException {
        TwakeCalendarConfiguration application = configuration("""
            twp.drive.enabled=false
            twp.drive.instance.domain=invalid
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
            .hasMessageContaining("twp.drive.instance.domain");
    }
}
