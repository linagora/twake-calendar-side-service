/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package com.linagora.calendar.dav;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.mockserver.model.HttpResponse.response;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.awaitility.Awaitility;
import org.bson.Document;
import org.bson.UuidRepresentation;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.TechnicalTokenService;
import com.linagora.calendar.storage.mongodb.MongoDBConfiguration;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoCommandMetricsListener;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

public class DockerSabreDavSetup {
    public static final DockerSabreDavSetup SINGLETON = new DockerSabreDavSetup();

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerSabreDavSetup.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public enum DockerService {
        MOCK_ESN("esn", 1080),
        RABBITMQ("rabbitmq", 5672),
        RABBITMQ_ADMIN("rabbitmq", 15672),
        SABRE_DAV("sabre_dav", 80),
        MONGO("mongo", 27017);

        private final String serviceName;
        private final Integer port;

        DockerService(String serviceName, Integer port) {
            this.serviceName = serviceName;
            this.port = port;
        }

        public String serviceName() {
            return serviceName;
        }

        public Integer port() {
            return port;
        }
    }

    public static final String DAV_ADMIN = "admin";
    public static final String DAV_ADMIN_PASSWORD = "secret123";
    private static final boolean TRUST_ALL_SSL_CERTS = true;
    private static final Duration MONGO_HEALTH_CHECK_TIMEOUT = Duration.ofMinutes(1);
    private static final Duration MONGO_HEALTH_CHECK_POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration MONGO_PING_ATTEMPT_TIMEOUT = Duration.ofSeconds(2);

    private final ComposeContainer environment;
    private SabreDavProvisioningService sabreDavProvisioningService;
    private MockServerClient mockServerClient;
    private MongoClient mongoClient;
    private MongoDatabase mongoDatabase;
    private volatile boolean started;
    private volatile boolean stopped;
    private boolean mockAuthenticationTokenEndpointConfigured;
    private volatile Thread shutdownHook;

    public DockerSabreDavSetup() {
        try {
            Path parentDirectory = Files.createTempDirectory("davIntegrationTests");
            Path dockerfilePath = Files.createTempFile(parentDirectory, "docker-sabre-dav-setup.yml", "");
            Files.copy(Objects.requireNonNull(DockerSabreDavSetup.class.getResourceAsStream("/" + "docker-sabre-dav-setup.yml")), dockerfilePath, StandardCopyOption.REPLACE_EXISTING);

            this.environment = new ComposeContainer(dockerfilePath.toFile())
                .withExposedService(DockerService.MOCK_ESN.serviceName(), DockerService.MOCK_ESN.port())
                .withExposedService(DockerService.RABBITMQ.serviceName(), DockerService.RABBITMQ.port())
                .withExposedService(DockerService.RABBITMQ_ADMIN.serviceName(), DockerService.RABBITMQ_ADMIN.port())
                .withExposedService(DockerService.SABRE_DAV.serviceName(), DockerService.SABRE_DAV.port())
                .withExposedService(DockerService.MONGO.serviceName(), DockerService.MONGO.port())
                .withLogConsumer(DockerService.RABBITMQ.serviceName(), frame -> LOGGER.debug("[{}] {}", DockerService.RABBITMQ.serviceName(), frame.getUtf8String().stripTrailing()))
                .withLogConsumer(DockerService.SABRE_DAV.serviceName(), frame -> LOGGER.debug("[{}] {}", DockerService.SABRE_DAV.serviceName(), frame.getUtf8String().stripTrailing()))
                .waitingFor(DockerService.SABRE_DAV.serviceName(), Wait.forLogMessage(".*ready to handle connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(5)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load or create temporary docker-compose file", e);
        }
    }

    public synchronized void start() {
        if (started) {
            LOGGER.debug("Sabre DAV Docker Compose stack already started");
            assertMongoIsHealthy();
            return;
        }
        Preconditions.checkState(!stopped, "DockerSabreDavSetup was already stopped and cannot be reused.");
        
        try {
            environment.start();
            for (DockerService dockerService : DockerService.values()) {
                LOGGER.debug("Started service: {} with mapping port: {}", dockerService.serviceName(), getPort(dockerService));
            }
            mongoClient = newMongoClient();
            mongoDatabase = mongoClient.getDatabase(SabreDavProvisioningService.DATABASE);
            assertMongoIsHealthy();
            sabreDavProvisioningService = new SabreDavProvisioningService(mongoDatabase);
            started = true;
        } catch (RuntimeException e) {
            stopNow();
            throw e;
        }
    }

    public void stop() {
        stopNow();
    }

    private synchronized void stopNow() {
        if (mockServerClient != null) {
            mockServerClient.close();
            mockServerClient = null;
        }
        mockAuthenticationTokenEndpointConfigured = false;
        if (mongoClient != null) {
            mongoClient.close();
            mongoClient = null;
            mongoDatabase = null;
        }
        environment.stop();
        sabreDavProvisioningService = null;
        started = false;
        stopped = true;
    }

    synchronized void stopOnJvmExit() {
        if (shutdownHook == null) {
            shutdownHook = new Thread(this::stopNow, "calendar-dav-docker-compose-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
        }
    }

    public ContainerState getRabbitMqContainer() {
        return environment.getContainerByServiceName(DockerService.RABBITMQ.serviceName()).orElseThrow();
    }

    public synchronized MockServerClient getMockServerClient() {
        Preconditions.checkState(started, "Sabre DAV Docker Compose stack should be started before creating MockServerClient");
        if (mockServerClient == null) {
            mockServerClient = new MockServerClient(getHost(DockerService.MOCK_ESN), getPort(DockerService.MOCK_ESN));
        }
        return mockServerClient;
    }

    public synchronized void setupMockAuthenticationTokenEndpoint() {
        if (mockAuthenticationTokenEndpointConfigured) {
            return;
        }

        MockServerClient client = getMockServerClient();
        client.when(HttpRequest.request()
                .withMethod("GET")
                .withPath("/api/technicalToken/introspect"))
            .respond(httpRequest -> {
                String token = httpRequest.getFirstHeader("X-TECHNICAL-TOKEN");
                return response()
                    .withStatusCode(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(buildTechnicalTokenIntrospectionResponseBody(token));
            });
        mockAuthenticationTokenEndpointConfigured = true;
    }

    private String buildTechnicalTokenIntrospectionResponseBody(String token) {
        return TECHNICAL_TOKEN_SERVICE_TESTING.claim(new TechnicalTokenService.JwtToken(token))
            .flatMap(tokenInfo -> new MongoDBOpenPaaSDomainDAO(mongoDatabase).retrieve(tokenInfo.domainId())
                .map(domain -> ImmutableMap.<String, Object>builder()
                    .put("domain", domain.domain().asString())
                    .putAll(tokenInfo.data())
                    .build()))
            .map(Throwing.function(objectMapper::writeValueAsString))
            .onErrorResume(e -> Mono.just("error: " + e.getMessage()))
            .block();
    }

    public URI rabbitMqUri() {
        return Throwing.supplier(() -> new URIBuilder()
            .setScheme("amqp")
            .setHost(getHost(DockerService.RABBITMQ))
            .setPort(getPort(DockerService.RABBITMQ))
            .build()).get();
    }

    public URI rabbitMqManagementUri() {
        return Throwing.supplier(() -> new URIBuilder()
            .setScheme("http")
            .setHost(getHost(DockerService.RABBITMQ_ADMIN))
            .setPort(getPort(DockerService.RABBITMQ_ADMIN))
            .build()).get();
    }

    public ContainerState getSabreDavContainer() {
        return environment.getContainerByServiceName(DockerService.SABRE_DAV.serviceName()).orElseThrow();
    }

    public URI getSabreDavURI() {
        return Throwing.supplier(() -> new URIBuilder()
            .setScheme("http")
            .setHost(getHost(DockerService.SABRE_DAV))
            .setPort(getPort(DockerService.SABRE_DAV))
            .build()).get();
    }

    public ContainerState getMongoDBContainer() {
        return environment.getContainerByServiceName(DockerService.MONGO.serviceName()).orElseThrow();
    }

    public URI getMongoDbIpAddress() {
        return Throwing.supplier(() -> new URIBuilder()
            .setScheme("mongodb")
            .setHost(getHost(DockerService.MONGO))
            .setPort(getPort(DockerService.MONGO))
            .build()).get();
    }

    public List<ContainerState> getAllContainers() {
        return List.of(getRabbitMqContainer(),
            getSabreDavContainer(),
            getMongoDBContainer());
    }

    public SabreDavProvisioningService getOpenPaaSProvisioningService() {
        Preconditions.checkNotNull(sabreDavProvisioningService, "OpenPaas Provisioning Service not initialized");
        return sabreDavProvisioningService;
    }

    public String getHost(DockerService dockerService) {
        return environment.getServiceHost(dockerService.serviceName(), dockerService.port());
    }

    public Integer getPort(DockerService dockerService) {
        return environment.getServicePort(dockerService.serviceName(), dockerService.port());
    }

    public DavConfiguration davConfiguration() {
        return new DavConfiguration(
            new UsernamePasswordCredentials(DAV_ADMIN, DAV_ADMIN_PASSWORD),
            getSabreDavURI(),
            Optional.of(TRUST_ALL_SSL_CERTS),
            Optional.empty(),
            Optional.empty());
    }

    public MongoDBConfiguration mongoDBConfiguration() {
        return new MongoDBConfiguration(getMongoDbIpAddress().toString(), SabreDavProvisioningService.DATABASE);
    }

    public MongoDatabase getMongoDB() {
        return Preconditions.checkNotNull(mongoDatabase, "MongoDB not initialized");
    }

    private MongoClient newMongoClient() {
        MongoDBConfiguration configuration = mongoDBConfiguration();
        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(configuration.mongoURL()))
            .applyToClusterSettings(builder -> builder.serverSelectionTimeout(MONGO_PING_ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .applyToSocketSettings(builder -> builder
                .connectTimeout((int) MONGO_PING_ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS)
                .readTimeout((int) MONGO_PING_ATTEMPT_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
            .addCommandListener(new MongoCommandMetricsListener(new RecordingMetricFactory()))
            .uuidRepresentation(UuidRepresentation.STANDARD)
            .build();

        return MongoClients.create(settings);
    }

    private void assertMongoIsHealthy() {
        MongoDatabase database = Preconditions.checkNotNull(mongoDatabase, "MongoDB not initialized");

        // SabreDAV readiness does not guarantee the host-mapped Mongo endpoint is usable yet.
        Awaitility.await()
            .pollInterval(MONGO_HEALTH_CHECK_POLL_INTERVAL)
            .atMost(MONGO_HEALTH_CHECK_TIMEOUT)
            .ignoreExceptions()
            .untilAsserted(() -> {
                ContainerState mongoContainer = getMongoDBContainer();
                Preconditions.checkState(mongoContainer.isRunning(), "MongoDB container should be running");
                Preconditions.checkState(mongoContainer.isHealthy(), "MongoDB container should be healthy");
                Mono.from(database.runCommand(new Document("ping", 1)))
                    .block(MONGO_PING_ATTEMPT_TIMEOUT);
            });
    }

    public RabbitMQConfiguration rabbitMQConfiguration() {
        return RabbitMQConfiguration.builder()
            .amqpUri(rabbitMqUri())
            .managementUri(rabbitMqManagementUri())
            .managementCredentials(new RabbitMQConfiguration.ManagementCredentials("calendar", "calendar".toCharArray()))
            .maxRetries(3)
            .minDelayInMs(10)
            .connectionTimeoutInMs(100)
            .channelRpcTimeoutInMs(5000)
            .handshakeTimeoutInMs(100)
            .shutdownTimeoutInMs(100)
            .networkRecoveryIntervalInMs(100)
            .build();
    }

    public HttpClient rabbitmqAdminHttpclient() {
        return HttpClient.create()
            .baseUrl(rabbitMqManagementUri().toString())
            .headers(headers -> {
                headers.add("Authorization", "Basic Y2FsZW5kYXI6Y2FsZW5kYXI="); // "calendar:calendar"
                headers.add("Content-Type", "application/json");
            });
    }
}