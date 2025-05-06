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

import static com.linagora.calendar.dav.DockerOpenPaasSetup.DockerService.MOCK_ESN;
import static org.mockserver.model.Parameter.param;

import org.junit.jupiter.api.extension.AfterAllCallback;
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

import com.linagora.calendar.storage.OpenPaaSUser;

public record DockerOpenPaasExtension(DockerOpenPaasSetup dockerOpenPaasSetup) implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(DockerOpenPaasExtension.class);
    private static MockServerClient mockServerClient;

    @Override
    public void beforeAll(ExtensionContext extensionContext) {
        dockerOpenPaasSetup.start();
        mockServerClient = new MockServerClient(  dockerOpenPaasSetup.getHost(MOCK_ESN),  dockerOpenPaasSetup.getPort(MOCK_ESN));
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) {
        dockerOpenPaasSetup.stop();
        mockServerClient.close();
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return (parameterContext.getParameter().getType() == DockerOpenPaasSetup.class);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return dockerOpenPaasSetup;
    }

    public OpenPaaSUser newTestUser() {
        OpenPaaSUser openPaasUser=  dockerOpenPaasSetup
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

}