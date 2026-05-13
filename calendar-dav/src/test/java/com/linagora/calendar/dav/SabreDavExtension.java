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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.calendar.dav;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.rabbitmq.client.BuiltinExchangeType.FANOUT;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.net.ssl.SSLException;

import org.bson.Document;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.linagora.calendar.storage.mongodb.MongoDBSecretLinkStore;
import com.linagora.calendar.storage.mongodb.MongoDBUploadedFileDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.rabbitmq.client.ConnectionFactory;

import reactor.core.publisher.Mono;
import reactor.rabbitmq.ExchangeSpecification;
import reactor.rabbitmq.RabbitFlux;
import reactor.rabbitmq.Sender;
import reactor.rabbitmq.SenderOptions;

public record SabreDavExtension(DockerSabreDavSetup dockerSabreDavSetup, DockerLifecycle dockerLifecycle) implements BeforeAllCallback, AfterAllCallback,
    AfterEachCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SabreDavExtension.class);
    private static final String DEFAULT_VHOST = "%2F";
    private static final List<String> CLEANUP_COLLECTIONS = List.of(
        MongoDBOpenPaaSDomainDAO.COLLECTION,
        MongoDBOpenPaaSUserDAO.COLLECTION,
        MongoDBUploadedFileDAO.COLLECTION,
        MongoDBSecretLinkStore.COLLECTION,
        MongoDBResourceDAO.COLLECTION);

    public enum DockerLifecycle {
        SHARED {
            @Override
            void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) {
                dockerSabreDavSetup.start();
                dockerSabreDavSetup.stopOnJvmExit();
            }

            @Override
            void afterAll(DockerSabreDavSetup dockerSabreDavSetup) {
            }
        },
        PER_CLASS {
            @Override
            void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) {
                dockerSabreDavSetup.start();
            }

            @Override
            void afterAll(DockerSabreDavSetup dockerSabreDavSetup) {
                dockerSabreDavSetup.stop();
            }
        };

        abstract void beforeAll(DockerSabreDavSetup dockerSabreDavSetup);

        abstract void afterAll(DockerSabreDavSetup dockerSabreDavSetup);
    }

    public SabreDavExtension(DockerSabreDavSetup dockerSabreDavSetup) {
        this(dockerSabreDavSetup, DockerLifecycle.SHARED);
    }

    public static SabreDavExtension shared() {
        return new SabreDavExtension(DockerSabreDavSetup.SINGLETON, DockerLifecycle.SHARED);
    }

    public static SabreDavExtension perClass() {
        return new SabreDavExtension(new DockerSabreDavSetup(), DockerLifecycle.PER_CLASS);
    }

    public SabreDavExtension {
        Objects.requireNonNull(dockerSabreDavSetup);
        Objects.requireNonNull(dockerLifecycle);
        if (dockerLifecycle == DockerLifecycle.SHARED && dockerSabreDavSetup != DockerSabreDavSetup.SINGLETON) {
            throw new IllegalArgumentException("SHARED requires DockerSabreDavSetup.SINGLETON. Use SabreDavExtension.perClass() for a dedicated stack.");
        }
        if (dockerLifecycle == DockerLifecycle.PER_CLASS && dockerSabreDavSetup == DockerSabreDavSetup.SINGLETON) {
            throw new IllegalArgumentException("PER_CLASS requires a dedicated DockerSabreDavSetup. Use SabreDavExtension.perClass().");
        }
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerLifecycle.beforeAll(dockerSabreDavSetup);
        provisionQueueExchanges();
        dockerSabreDavSetup.setupMockAuthenticationTokenEndpoint();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        dockerLifecycle.afterAll(dockerSabreDavSetup);
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        cleanupMongoCollections();
        provisionQueueExchanges();
    }

    private void cleanupMongoCollections() {
        MongoDatabase mongoDatabase = dockerSabreDavSetup.getMongoDB();
        for (String collection : CLEANUP_COLLECTIONS) {
            Mono.from(mongoDatabase.getCollection(collection).deleteMany(new Document())).block();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerSabreDavSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return dockerSabreDavSetup;
    }

    public OpenPaaSUser newTestUser() {
        return newTestUser(Optional.empty());
    }

    public OpenPaaSUser newTestUser(Optional<String> prefix) {
        OpenPaaSUser openPaasUser = dockerSabreDavSetup
            .getOpenPaaSProvisioningService()
            .createUser(prefix)
            .block();

        return openPaasUser;
    }

    private void provisionQueueExchanges() {
        LOGGER.debug("Provisioning RabbitMQ exchanges...");
        try {
            var factory = new ConnectionFactory();
            factory.setUri(dockerSabreDavSetup.rabbitMqUri().toASCIIString());
            factory.setUsername("calendar");
            factory.setPassword("calendar");

            try (Sender sender = RabbitFlux.createSender(new SenderOptions().connectionFactory(factory))) {
                DavExchangeNames.ALL.forEach(name -> sender.declareExchange(
                        ExchangeSpecification.exchange(name)
                            .durable(true)
                            .type(FANOUT.getType()))
                    .doOnSuccess(v -> LOGGER.debug("Declared exchange: {}", name))
                    .block());
            }

            LOGGER.debug("Provisioned {} RabbitMQ exchanges successfully", DavExchangeNames.ALL.size());
        } catch (Exception e) {
            throw new RuntimeException("Failed to provision RabbitMQ exchanges", e);
        }
    }

    public void deleteRabbitMQQueues(String... queueNames) {
        for (String queueName : queueNames) {
            deleteRabbitMQQueue(queueName);
        }
    }

    private void deleteRabbitMQQueue(String queueName) {
        dockerSabreDavSetup.rabbitmqAdminHttpclient()
            .delete()
            .uri("/api/queues/" + DEFAULT_VHOST + "/" + encode(queueName))
            .responseSingle((response, body) -> {
                int statusCode = response.status().code();
                if (statusCode == 204 || statusCode == 404) {
                    return Mono.empty();
                }
                return body.asString(StandardCharsets.UTF_8)
                    .defaultIfEmpty("")
                    .flatMap(errorBody -> Mono.error(new IllegalStateException(
                        "Failed to delete RabbitMQ queue " + queueName + ": " + statusCode + " " + errorBody)));
            })
            .block();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public DavTestHelper davTestHelper() {
        try {
            return new DavTestHelper(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }
}