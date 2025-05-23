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
import static org.mockserver.model.Parameter.param;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.bson.Document;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBSecretLinkStore;
import com.linagora.calendar.storage.mongodb.MongoDBUploadedFileDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;

public record SabreDavExtension(DockerSabreDavSetup dockerSabreDavSetup) implements BeforeAllCallback, AfterAllCallback,
    AfterEachCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(SabreDavExtension.class);
    private static final List<String> CLEANUP_COLLECTIONS = List.of(
        MongoDBOpenPaaSDomainDAO.COLLECTION,
        MongoDBOpenPaaSUserDAO.COLLECTION,
        MongoDBUploadedFileDAO.COLLECTION,
        MongoDBSecretLinkStore.COLLECTION);

    private static MockServerClient mockServerClient;

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerSabreDavSetup.start();
        mockServerClient = new MockServerClient(dockerSabreDavSetup.getHost(MOCK_ESN), dockerSabreDavSetup.getPort(MOCK_ESN));
        waitForRabbitMQToBeReady();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        dockerSabreDavSetup.stop();
        if (mockServerClient != null) {
            mockServerClient.stop();
        }
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
        OpenPaaSUser openPaasUser = dockerSabreDavSetup
            .getOpenPaaSProvisioningService()
            .createUser()
            .block();

        setupUserLookupByEmail(openPaasUser.username().asString(), openPaasUser.id().value());
        return openPaasUser;
    }

    private void setupUserLookupByEmail(String emailAddress, String id) {
        mockServerClient
            .when(HttpRequest.request()
                .withMethod("GET")
                .withPath("/api/users")
                .withQueryStringParameter(param("email", emailAddress)))
            .respond(HttpResponse.response()
                .withStatusCode(200)
                .withHeader(new Header("Content-Type", "application/json"))
                .withBody("[{\"_id\": \"" + id + "\"}]"));

        LOGGER.debug("Mocked user by email: {} with id: {}", emailAddress, id);
    }

    private boolean importRabbitMQDefinitions() {
        try {
            Path parentDirectory = Files.createTempDirectory("davIntegrationTests");
            Path definitionFilePath = Files.createTempFile(parentDirectory, "rabbitmq-definitions.json", "");
            Files.copy(Objects.requireNonNull(SabreDavExtension.class.getResourceAsStream("/" + "rabbitmq-definitions.json")), definitionFilePath, StandardCopyOption.REPLACE_EXISTING);

            dockerSabreDavSetup.rabbitmqAdminHttpclient().post()
                .uri("/api/definitions")
                .send(ByteBufFlux.fromPath(definitionFilePath))
                .responseSingle((res, bytes) -> {
                    if (res.status().code() == 204) {
                        LOGGER.info("Successfully imported RabbitMQ definitions (HTTP 204)");
                        return Mono.empty();
                    } else {
                        return bytes.asString()
                            .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                            .doOnNext(body ->
                                LOGGER.warn("Unexpected response from RabbitMQ (status={}): {}",
                                    res.status().code(), body));
                    }
                })
                .block();

            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to import RabbitMQ definitions", e);
            return false;
        }
    }

    private void waitForRabbitMQToBeReady() {
        Awaitility.await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(this::importRabbitMQDefinitions);
    }

}