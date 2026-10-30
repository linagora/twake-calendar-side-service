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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.OptionalBinder;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStorageClient;

/** Installed by the application only when the Drive integration is enabled. */
public final class TDriveModule extends AbstractModule {
    @Override
    protected void configure() {
        OptionalBinder.newOptionalBinder(binder(), AttachmentStorageClient.class)
            .setBinding().toProvider(ClientProvider.class).in(Singleton.class);
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

    public static final class ClientProvider implements Provider<AttachmentStorageClient> {
        private final TDriveConfiguration configuration;

        @jakarta.inject.Inject
        public ClientProvider(TDriveConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public AttachmentStorageClient get() {
            return new TDriveAttachmentStorageClient(configuration);
        }
    }

}
