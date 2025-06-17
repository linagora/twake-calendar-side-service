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

package com.linagora.calendar.smtp;

import java.util.UUID;

import org.apache.james.mock.smtp.server.ConfigurationClient;
import org.apache.james.util.Host;
import org.apache.james.util.docker.DockerContainer;
import org.apache.james.util.docker.Images;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.wait.strategy.Wait;

public class MockSmtpServerExtension implements AfterEachCallback, BeforeAllCallback,
    AfterAllCallback, ParameterResolver {

    public static class DockerMockSmtp {
        private static final Logger LOGGER = LoggerFactory.getLogger(DockerMockSmtp.class);

        private final DockerContainer mockSmtpServer;

        DockerMockSmtp() {
            mockSmtpServer = DockerContainer.fromName(Images.MOCK_SMTP_SERVER)
                .withLogConsumer(outputFrame -> LOGGER.debug("MockSMTP: {}", outputFrame.getUtf8String()))
                .withExposedPorts(25, 8000)
                .waitingFor(Wait.forLogMessage(".*Mock SMTP server started.*", 1))
                .withName("james-testing-mock-smtp-server-" + UUID.randomUUID());
        }

        void start() {
            mockSmtpServer.start();
        }

        void stop() {
            mockSmtpServer.stop();
        }

        public ConfigurationClient getConfigurationClient() {
            return ConfigurationClient.from(Host.from(
                mockSmtpServer.getHostIp(),
                mockSmtpServer.getMappedPort(8000)));
        }

        public String getIPAddress() {
            return mockSmtpServer.getContainerIp();
        }

        public int getSmtpPort() {
            return mockSmtpServer.getMappedPort(25);
        }

        public int getRestApiPort() {
            return mockSmtpServer.getMappedPort(8000);
        }
    }

    private final DockerMockSmtp dockerMockSmtp;

    public MockSmtpServerExtension() {
        this.dockerMockSmtp = new DockerMockSmtp();
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        dockerMockSmtp.start();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        dockerMockSmtp.getConfigurationClient()
            .cleanServer();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        dockerMockSmtp.stop();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return parameterContext.getParameter().getType() == DockerMockSmtp.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return dockerMockSmtp;
    }

    public DockerMockSmtp getMockSmtp() {
        return dockerMockSmtp;
    }
}
