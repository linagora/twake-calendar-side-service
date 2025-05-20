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

package com.linagora.calendar.dav;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.linagora.calendar.storage.mongodb.MongoDBConfiguration;

public class DavModuleTestHelper {

    public static final Module BY_PASS_MODULE = new AbstractModule() {

        @Provides
        @Singleton
        public DavConfiguration provideDavConfiguration() throws URISyntaxException {
            return new DavConfiguration(
                new UsernamePasswordCredentials("dummy", "dummy"),
                new URI("http://localhost:8080"),
                Optional.of(true),
                Optional.of(Duration.ofMillis(500)));
        }
    };

    public static final Function<RabbitMQExtension, Module> RABBITMQ_MODULE = rabbitMQExtension -> new AbstractModule() {
        @Provides
        @Singleton
        public RabbitMQConfiguration provideRabbitMQConfiguration() throws URISyntaxException {
            return RabbitMQConfiguration.builder()
                .amqpUri(rabbitMQExtension.getRabbitMQ().amqpUri())
                .managementUri(rabbitMQExtension.getRabbitMQ().managementUri())
                .managementCredentials(new RabbitMQConfiguration.ManagementCredentials("guest", "guest".toCharArray()))
                .maxRetries(3)
                .minDelayInMs(10)
                .connectionTimeoutInMs(100)
                .channelRpcTimeoutInMs(100)
                .handshakeTimeoutInMs(100)
                .shutdownTimeoutInMs(100)
                .networkRecoveryIntervalInMs(100)
                .build();
        }
    };

    public static final Function<SabreDavExtension, Module> FROM_SABRE_EXTENSION = dockerSabreDavSetup -> new AbstractModule() {
        @Override
        protected void configure() {
            bind(DavConfiguration.class).toInstance(dockerSabreDavSetup.dockerSabreDavSetup().davConfiguration());
            bind(MongoDBConfiguration.class).toInstance(dockerSabreDavSetup.dockerSabreDavSetup().mongoDBConfiguration());
            bind(RabbitMQConfiguration.class).toInstance(dockerSabreDavSetup.dockerSabreDavSetup().rabbitMQConfiguration());
        }
    };
}