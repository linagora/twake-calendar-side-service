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

package com.linagora.calendar.amqp.meet;

import java.io.FileNotFoundException;

import javax.net.ssl.SSLException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/**
 * Meet integration foundation: configuration, application-scoped HTTP
 * client and token provider. Feature modules (host delegation, room
 * creation…) build on these bindings.
 */
public class MeetIntegrationModule extends AbstractModule {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetIntegrationModule.class);

    @Provides
    @Singleton
    public MeetConfiguration provideMeetConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            return MeetConfiguration.from(propertiesProvider.getConfiguration("configuration"));
        } catch (FileNotFoundException e) {
            LOGGER.info("configuration.properties not found — Meet integration stays disabled");
            return MeetConfiguration.disabled();
        }
    }

    @Provides
    @Singleton
    public MeetApplicationClient provideMeetApplicationClient(MeetConfiguration configuration) throws SSLException {
        return new MeetApplicationClient(configuration);
    }

    @Provides
    @Singleton
    public MeetTokenProvider provideMeetTokenProvider(MeetConfiguration configuration) throws SSLException {
        return new MeetApplicationCredentialsTokenProvider(configuration);
    }
}
