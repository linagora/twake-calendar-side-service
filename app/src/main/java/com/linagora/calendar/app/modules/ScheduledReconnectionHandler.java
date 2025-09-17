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

package com.linagora.calendar.app.modules;

import static com.rabbitmq.client.ConnectionFactory.DEFAULT_VHOST;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import jakarta.annotation.PreDestroy;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.lifecycle.api.Startable;
import org.apache.james.util.DurationParser;
import org.apache.james.utils.PropertiesProvider;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import com.linagora.calendar.amqp.CalendarQueueUtil;

import feign.Client;
import feign.Feign;
import feign.Param;
import feign.RequestLine;
import feign.RetryableException;
import feign.Retryer;
import feign.auth.BasicAuthRequestInterceptor;
import feign.codec.ErrorDecoder;
import feign.jackson.JacksonDecoder;
import feign.jackson.JacksonEncoder;
import feign.slf4j.Slf4jLogger;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * This class is based on the implementation from {@code com.linagora.tmail.ScheduledReconnectionHandler}.
 */
public class ScheduledReconnectionHandler implements Startable {
    public record ScheduledReconnectionHandlerConfiguration(boolean enabled, Duration interval) {
        public static final boolean ENABLED = true;
        public static final Duration ONE_MINUTE = Duration.ofSeconds(60);

        public static ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration parse(PropertiesProvider propertiesProvider) throws ConfigurationException {
            try {
                Configuration configuration = propertiesProvider.getConfiguration("rabbitmq");
                boolean enabled = configuration.getBoolean("scheduled.consumer.reconnection.enabled", ENABLED);
                Duration interval = Optional.ofNullable(configuration.getString("scheduled.consumer.reconnection.interval", null))
                    .map(s -> DurationParser.parse(s, ChronoUnit.SECONDS))
                    .orElse(ONE_MINUTE);

                return new ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration(enabled, interval);
            } catch (FileNotFoundException e) {
                return new ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration(false, ONE_MINUTE);
            }
        }
    }

    public interface RabbitMQManagementAPI {

        @RequestLine(value = "GET /api/queues/{vhost}/{name}", decodeSlash = false)
        RabbitMQManagementAPI.MessageQueueDetails queueDetails(@Param("vhost") String vhost, @Param("name") String name);

        @JsonIgnoreProperties(ignoreUnknown = true)
        record MessageQueueDetails(
            @JsonProperty("name") String name,
            @JsonProperty("vhost") String vhost,
            @JsonProperty("consumer_details") List<RabbitMQManagementAPI.ConsumerDetails> consumerDetails) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        record ConsumerDetails(
            @JsonProperty("consumer_tag") String tag,
            @JsonProperty("activity_status") String status) {
        }

        static RabbitMQManagementAPI from(RabbitMQConfiguration configuration) {
            try {
                var credentials = configuration.getManagementCredentials();

                return Feign.builder()
                    .client(getClient(configuration))
                    .requestInterceptor(new BasicAuthRequestInterceptor(
                        credentials.getUser(), new String(credentials.getPassword())))
                    .logger(new Slf4jLogger(RabbitMQManagementAPI.class))
                    .logLevel(feign.Logger.Level.FULL)
                    .encoder(new JacksonEncoder())
                    .decoder(new JacksonDecoder())
                    .retryer(new Retryer.Default())
                    .errorDecoder(RETRY_500)
                    .target(RabbitMQManagementAPI.class, configuration.getManagementUri().toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private static Client getClient(RabbitMQConfiguration config)
            throws GeneralSecurityException, IOException {

            if (!config.useSslManagement()) {
                return new Client.Default(null, null);
            }

            var sslContextBuilder = new SSLContextBuilder();
            setupSslValidationStrategy(sslContextBuilder, config);
            setupClientCertificateAuthentication(sslContextBuilder, config);
            SSLContext sslContext = sslContextBuilder.build();

            return new Client.Default(sslContext.getSocketFactory(), getHostNameVerifier(config));
        }

        private static void setupClientCertificateAuthentication(SSLContextBuilder builder, RabbitMQConfiguration config) {
            config.getSslConfiguration().getKeyStore()
                .ifPresent(ks -> {
                    try {
                        builder.loadKeyMaterial(ks.getFile(), ks.getPassword(), ks.getPassword());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to load key material", e);
                    }
                });
        }

        private static void setupSslValidationStrategy(SSLContextBuilder builder, RabbitMQConfiguration config)
            throws GeneralSecurityException, IOException {

            var strategy = config.getSslConfiguration().getStrategy();

            switch (strategy) {
                case DEFAULT -> {
                }
                case IGNORE -> builder.loadTrustMaterial((x509Certs, authType) -> true);
                case OVERRIDE -> applyTrustStore(builder, config);
                default -> throw new NotImplementedException("Unknown strategy: " + strategy);
            }
        }

        private static SSLContextBuilder applyTrustStore(SSLContextBuilder builder, RabbitMQConfiguration config)
            throws GeneralSecurityException, IOException {

            var trustStore = config.getSslConfiguration().getTrustStore()
                .orElseThrow(() -> new IllegalStateException("SSLTrustStore must not be empty"));

            return builder.loadTrustMaterial(trustStore.getFile(), trustStore.getPassword());
        }

        private static HostnameVerifier getHostNameVerifier(RabbitMQConfiguration config) {
            return switch (config.getSslConfiguration().getHostNameVerifier()) {
                case ACCEPT_ANY_HOSTNAME -> (hostname, session) -> true;
                default -> new DefaultHostnameVerifier();
            };
        }

        class QueueNotFoundException extends RuntimeException {
        }

        ErrorDecoder RETRY_500 = (methodKey, response) -> switch (response.status()) {
            case HttpStatus.NOT_FOUND_404 -> new RabbitMQManagementAPI.QueueNotFoundException();
            case HttpStatus.INTERNAL_SERVER_ERROR_500 -> new RetryableException(
                response.status(), "Retry due to server error",
                response.request().httpMethod(), new Date().getTime(), response.request());
            default -> new RuntimeException("Unhandled response status: " + response.status());
        };
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledReconnectionHandler.class);
    private static final Duration DELAY_START = DurationParser.parse(System.getProperty("scheduled.consumer.reconnection.delayStartUp", "30s"));
    private static final Duration DELAY_ON_EACH_COMPLETED = Duration.ofSeconds(30);

    private final Set<SimpleConnectionPool.ReconnectionHandler> reconnectionHandlers;
    private final RabbitMQManagementAPI mqManagementAPI;
    private final RabbitMQConfiguration configuration;
    private final SimpleConnectionPool connectionPool;
    private final ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration config;
    private final ImmutableList<String> queuesToMonitor;
    private Disposable disposable;

    @Inject
    public ScheduledReconnectionHandler(Set<SimpleConnectionPool.ReconnectionHandler> reconnectionHandlers,
                                        RabbitMQConfiguration configuration,
                                        SimpleConnectionPool connectionPool,
                                        ScheduledReconnectionHandler.ScheduledReconnectionHandlerConfiguration config) {
        this.reconnectionHandlers = reconnectionHandlers;
        this.mqManagementAPI = RabbitMQManagementAPI.from(configuration);
        this.configuration = configuration;
        this.connectionPool = connectionPool;
        this.config = config;

        this.queuesToMonitor = ImmutableList.copyOf(CalendarQueueUtil.getAllQueueNames());
    }

    @VisibleForTesting
    public ImmutableList<String> getQueuesToMonitor() {
        return queuesToMonitor;
    }

    public void start() {
        if (!config.enabled()) {
            return;
        }
        disposable = Mono.delay(DELAY_START)
            .thenMany(Flux.interval(config.interval()))
            .filter(any -> restartNeeded())
            .concatMap(any -> restart().then(Mono.delay(DELAY_ON_EACH_COMPLETED)))
            .onErrorResume(e -> {
                LOGGER.warn("Failed to run scheduled RabbitMQ consumer checks", e);
                return Mono.empty();
            })
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe();
    }

    @PreDestroy
    public void stop() {
        Optional.ofNullable(disposable).ifPresent(Disposable::dispose);
    }

    private Mono<Void> restart() {
        LOGGER.warn("One of the queues has no consumer thus restarting all consumers");
        return connectionPool.getResilientConnection()
            .flatMap(connection -> Flux.fromIterable(reconnectionHandlers)
                .concatMap(h -> h.handleReconnection(connection))
                .then());
    }

    public boolean restartNeeded() {
        return queuesToMonitor.stream().anyMatch(this::queueHasNoConsumers);
    }

    private boolean queueHasNoConsumers(String queue) {
        try {
            boolean hasConsumers = !mqManagementAPI.queueDetails(configuration.getVhost().orElse(DEFAULT_VHOST), queue)
                .consumerDetails()
                .isEmpty();

            if (!hasConsumers) {
                LOGGER.warn("The {} queue has no consumers", queue);
            }

            return !hasConsumers;
        } catch (RabbitMQManagementAPI.QueueNotFoundException e) {
            return false;
        }
    }
}