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

import static com.linagora.calendar.dav.DockerSabreDavSetup.DockerService.MOCK_ESN;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static com.rabbitmq.client.BuiltinExchangeType.FANOUT;
import static org.mockserver.model.HttpResponse.response;

import java.util.List;
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
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TechnicalTokenService;
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

public record SabreDavExtension(DockerSabreDavSetup dockerSabreDavSetup) implements BeforeAllCallback, AfterAllCallback,
    AfterEachCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SabreDavExtension.class);
    private static final List<String> CLEANUP_COLLECTIONS = List.of(
        MongoDBOpenPaaSDomainDAO.COLLECTION,
        MongoDBOpenPaaSUserDAO.COLLECTION,
        MongoDBUploadedFileDAO.COLLECTION,
        MongoDBSecretLinkStore.COLLECTION,
        MongoDBResourceDAO.COLLECTION);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static MockServerClient mockServerClient;

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerSabreDavSetup.start();
        mockServerClient = new MockServerClient(dockerSabreDavSetup.getHost(MOCK_ESN), dockerSabreDavSetup.getPort(MOCK_ESN));
        provisionQueueExchanges();
        setupMockAuthenticationTokenEndpoint();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        if (mockServerClient != null) {
            mockServerClient.close();
        }
        dockerSabreDavSetup.stop();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
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

    private void setupMockAuthenticationTokenEndpoint() {
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("GET")
                .withPath("/api/technicalToken/introspect"))
            .respond(httpRequest -> {
                String token = httpRequest.getFirstHeader("X-TECHNICAL-TOKEN");
                return response()
                    .withStatusCode(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(TECHNICAL_TOKEN_SERVICE_TESTING.claim(new TechnicalTokenService.JwtToken(token))
                        .map(Throwing.function(tokenInfo -> objectMapper.writeValueAsString(tokenInfo.data())))
                        .onErrorResume(e -> Mono.just("error: " + e.getMessage()))
                        .block());
            });
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

    public DavTestHelper davTestHelper() {
        try {
            return new DavTestHelper(dockerSabreDavSetup.davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        } catch (SSLException e) {
            throw new RuntimeException(e);
        }
    }
}