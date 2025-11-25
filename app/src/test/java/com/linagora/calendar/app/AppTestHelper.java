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
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.inject.Named;
import reactor.core.publisher.Mono;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventBusName;
import org.apache.james.events.EventListener;
import org.apache.james.events.Group;
import org.apache.james.events.GroupAlreadyRegistered;
import org.apache.james.events.Registration;
import org.apache.james.events.RegistrationKey;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.name.Names;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.auth.LemonCookieAuthenticationStrategy;
import com.linagora.calendar.restapi.auth.LemonCookieAuthenticationStrategy.ResolutionConfiguration;
import com.linagora.calendar.restapi.auth.OidcAuthenticationStrategy;
import com.linagora.calendar.restapi.auth.OidcFallbackCookieAuthenticationStrategy;
import com.linagora.calendar.storage.SimpleSessionProvider;

public class AppTestHelper {

    public static class FakeEventBus implements EventBus {
        @Override
        public Publisher<Registration> register(EventListener.ReactiveEventListener listener, RegistrationKey key) {
            return Mono.empty();
        }

        @Override
        public Registration register(EventListener.ReactiveEventListener listener, Group group) throws GroupAlreadyRegistered {
            return null;
        }

        @Override
        public Mono<Void> dispatch(Event event, Set<RegistrationKey> key) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> reDeliver(Group group, Event event) {
            return Mono.empty();
        }

        @Override
        public EventBusName eventBusName() {
            return new EventBusName("FakeEventBus");
        }
    }

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

    public static final Module EVENT_BUS_BY_PASS_MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            bind(EventBus.class).to(FakeEventBus.class);
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
        @Named("oidcAuthenticationStrategy")
        public AuthenticationStrategy provideprovideOidcAuthenticationStrategy(OidcAuthenticationStrategy oidcAuthenticationStrategy,
            SimpleSessionProvider sessionProvider) {
            LemonCookieAuthenticationStrategy lemonCookieAuthenticationStrategy = new LemonCookieAuthenticationStrategy(resolutionConfiguration, sessionProvider);
            return new OidcFallbackCookieAuthenticationStrategy(oidcAuthenticationStrategy, lemonCookieAuthenticationStrategy);
        }
    };
}
