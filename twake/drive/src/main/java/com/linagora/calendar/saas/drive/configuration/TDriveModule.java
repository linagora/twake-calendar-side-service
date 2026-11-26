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

package com.linagora.calendar.saas.drive.configuration;

import java.io.FileNotFoundException;
import java.util.Optional;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.OptionalBinder;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
import com.linagora.calendar.saas.drive.tdrive.ClouderyTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.LdapTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.LocalPartTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStore;
import com.linagora.calendar.saas.drive.tdrive.TDriveInstanceResolver;
import com.unboundid.ldap.sdk.LDAPConnectionPool;

/** Installed by the application only when the Drive integration is enabled. */
public final class TDriveModule extends AbstractModule {
    @Override
    protected void configure() {
        OptionalBinder.newOptionalBinder(binder(), LdapRepositoryConfiguration.class);
        OptionalBinder.newOptionalBinder(binder(), LDAPConnectionPool.class);
        OptionalBinder.newOptionalBinder(binder(), AttachmentStore.class)
            .setBinding().to(TDriveAttachmentStore.class).in(Singleton.class);
    }

    @Provides
    @Singleton
    public TDriveConfiguration provideTDriveConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            return TDriveConfiguration.enabled(propertiesProvider.getConfiguration("configuration"));
        } catch (FileNotFoundException e) {
            throw new ConfigurationException("configuration.properties is required to configure the TDrive integration", e);
        }
    }

    @Provides
    @Singleton
    TDriveInstanceResolver provideInstanceResolver(TDriveConfiguration configuration,
                                                 Optional<LdapRepositoryConfiguration> ldapConfiguration,
                                                 Optional<LDAPConnectionPool> ldapConnectionPool) {
        return switch (configuration.instanceResolver()) {
            case LOCAL_PART -> new LocalPartTDriveInstanceResolver(configuration.localPartDomain());
            case CLOUDERY -> new ClouderyTDriveInstanceResolver(
                configuration.clouderyLookup().orElseThrow(() -> new IllegalArgumentException("Cloudery lookup configuration is required")),
                configuration.timeout(), configuration.trustAllSslCerts());
            case LDAP -> new LdapTDriveInstanceResolver(
                ldapConfiguration.orElseThrow(() -> new IllegalArgumentException("LDAP Drive resolver requires LDAP user configuration")),
                ldapConnectionPool.orElseThrow(() -> new IllegalArgumentException("LDAP Drive resolver requires an LDAP connection pool")));
        };
    }
}
