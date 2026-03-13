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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.utils.URIBuilder;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.ContainerState;
import org.testcontainers.containers.wait.strategy.Wait;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.mongodb.MongoDBConfiguration;
import com.linagora.calendar.storage.mongodb.MongoDBConnectionFactory;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class DockerSabreDavSetup {
    public static final DockerSabreDavSetup SINGLETON = new DockerSabreDavSetup();

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerSabreDavSetup.class);
    private static final String POSTGRES_USERNAME = "username";
    private static final String POSTGRES_PASSWORD = "password";
    private static final String POSTGRES_DATABASE = "postgres";

    public enum DockerService {
        MOCK_ESN("esn", 1080),
        RABBITMQ("rabbitmq", 5672),
        RABBITMQ_ADMIN("rabbitmq", 15672),
        SABRE_DAV("sabre_dav", 80),
        MONGO("mongo", 27017),
        POSTGRES("postgres", 5432),;

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

    private final ComposeContainer environment;
    private SabreDavProvisioningService sabreDavProvisioningService;

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
                .withExposedService(DockerService.POSTGRES.serviceName(), DockerService.POSTGRES.port(),
                    Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)))
                .withLogConsumer(DockerService.RABBITMQ.serviceName(), frame -> LOGGER.info("[SABRE_DAV] " + frame.getUtf8String()))
                .waitingFor(DockerService.SABRE_DAV.serviceName(), Wait.forLogMessage(".*ready to handle connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(5)));
        } catch (IOException e) {
            throw new RuntimeException("Failed to load or create temporary docker-compose file", e);
        }
    }

    public void start() {
        environment.start();
        patchPostgresAuth();
        for (DockerService dockerService : DockerService.values()) {
            LOGGER.debug("Started service: {} with mapping port: {}", dockerService.serviceName(), getPort(dockerService));
        }
        sabreDavProvisioningService = new SabreDavProvisioningService(getMongoDbIpAddress().toString());
    }

    public void stop() {
        environment.stop();
    }

    public ContainerState getRabbitMqContainer() {
        return environment.getContainerByServiceName(DockerService.RABBITMQ.serviceName()).orElseThrow();
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
            .setUserInfo(POSTGRES_USERNAME, POSTGRES_PASSWORD)
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
        return MongoDBConnectionFactory.instantiateDB(mongoDBConfiguration(), new RecordingMetricFactory());
    }

    public RabbitMQConfiguration rabbitMQConfiguration() {
        return RabbitMQConfiguration.builder()
            .amqpUri(rabbitMqUri())
            .managementUri(rabbitMqManagementUri())
            .managementCredentials(new RabbitMQConfiguration.ManagementCredentials("calendar", "calendar".toCharArray()))
            .maxRetries(3)
            .minDelayInMs(10)
            .connectionTimeoutInMs(100)
            .channelRpcTimeoutInMs(100)
            .handshakeTimeoutInMs(100)
            .shutdownTimeoutInMs(100)
            .networkRecoveryIntervalInMs(100)
            .build();
    }

    /**
     * Patch PostgreSQL SCRAM authentication to be compatible with MongoDB clients.
     * <p>
     * mongo-c-driver uses a 28-byte salt for SCRAM-SHA-256, while PostgreSQL uses 16 bytes.
     * We update `pg_authid.rolpassword` with a MongoDB-compatible hash to avoid
     * authentication issues during integration tests.
     */
    private void patchPostgresAuth() {
        ContainerState postgres = environment
            .getContainerByServiceName(DockerService.POSTGRES.serviceName())
            .orElseThrow();

        try {
            Container.ExecResult execResult = postgres.execInContainer(
                "psql",
                "-U", POSTGRES_USERNAME,
                "-d", POSTGRES_DATABASE,
                "-c",
                "UPDATE pg_authid " +
                    "SET rolpassword = 'SCRAM-SHA-256$4096:dm4MIMl6+sUG6p/eeOGsYEYFs1y7UcbrLnbBOg==$cRTjw3T3qpNaLceUY9UVoUC87LJhcIf/mLaNS0sI/qo=:8Fc3Chlq4vXgDzG2xnBA4ds0gnAt6KLBKd8yFeHRLBE=' " +
                    "WHERE rolname = '" + POSTGRES_USERNAME + "';");
            if (execResult.getExitCode() == 0) {
                LOGGER.info("PostgreSQL pg_authid patched successfully");
            } else {
                throw new RuntimeException("PostgreSQL pg_authid failed with exit code " + execResult.getExitCode() + " " + execResult.getStderr());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to patch PostgreSQL pg_authid", e);
        }
    }
}