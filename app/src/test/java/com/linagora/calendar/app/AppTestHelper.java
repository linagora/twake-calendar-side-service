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

import static com.linagora.calendar.dav.DavModuleTestHelper.RABBITMQ_MODULE;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.name.Names;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.auth.LemonCookieAuthenticationStrategy;
import com.linagora.calendar.restapi.auth.LemonCookieAuthenticationStrategy.ResolutionConfiguration;
import com.linagora.calendar.restapi.auth.SimpleSessionProvider;

public class AppTestHelper {

    public static final String COOKIE_RESOLUTION_PATH = "/mysession/whoami";

    public static final Function<RabbitMQExtension, Module> BY_PASS_MODULE = rabbitMQExtension -> new AbstractModule() {
        @Override
        protected void configure() {
            install(OIDC_BY_PASS_MODULE);
            install(DavModuleTestHelper.BY_PASS_MODULE);
            install(RABBITMQ_MODULE.apply(rabbitMQExtension));
        }
    };

    public static final Module OIDC_BY_PASS_MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            install(LEMON_COOKIE_AUTHENTICATION_STRATEGY_BY_PASS_MODULE);

            bind(URL.class).annotatedWith(Names.named("userInfo"))
                .toProvider(() -> {
                    try {
                        return new URL("https://neven.to.be.called.com");
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    };

    public static final Function<DockerOpenSearchExtension, Module> OPENSEARCH_TEST_MODULE = dockerOpenSearchExtension ->
        new AbstractModule() {
            @Override
            protected void configure() {
                bind(OpenSearchConfiguration.class)
                    .toInstance(OpenSearchConfiguration.builder()
                        .addHost(dockerOpenSearchExtension.getDockerOpenSearch().getHttpHost())
                        .requestTimeout(Optional.of(Duration.ofSeconds(5)))
                        .build());
            }
        };

    public static final Function<ResolutionConfiguration, Module> LEMON_COOKIE_AUTHENTICATION_STRATEGY_MODULE = resolutionConfiguration -> new AbstractModule() {

        @Provides
        @Singleton
        public LemonCookieAuthenticationStrategy provideLemonCookieAuthenticationStrategy(SimpleSessionProvider sessionProvider) {
            return new LemonCookieAuthenticationStrategy(resolutionConfiguration, sessionProvider);
        }
    };

    public static final Module LEMON_COOKIE_AUTHENTICATION_STRATEGY_BY_PASS_MODULE = new AbstractModule() {

        @Provides
        @Singleton
        public LemonCookieAuthenticationStrategy provideLemonCookieAuthenticationStrategy(SimpleSessionProvider sessionProvider) {
            Domain domain = Domain.of("neven.to.be.called.com");
            ResolutionConfiguration resolutionConfiguration = new ResolutionConfiguration(URI.create("https://neven.to.be.called.com"),
                domain,
                domain);
            return new LemonCookieAuthenticationStrategy(resolutionConfiguration, sessionProvider);
        }
    };
}
