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

package com.linagora.calendar.app.restapi.routes;

import static com.linagora.calendar.app.AppTestHelper.BY_PASS_MODULE;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static io.restassured.http.ContentType.JSON;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Function;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TechnicalTokenService;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class CheckTechnicalUserTokenRouteTest {

    static class TechnicalTokenProbe implements GuiceProbe {
        private final TechnicalTokenService technicalTokenService;
        private final OpenPaaSDomainDAO domainDAO;

        @Inject
        public TechnicalTokenProbe(TechnicalTokenService technicalTokenService, OpenPaaSDomainDAO domainDAO) {
            this.technicalTokenService = technicalTokenService;
            this.domainDAO = domainDAO;
        }

        public String generateToken(Domain domain) {
            return domainDAO.retrieve(domain)
                .flatMap(openPaaSDomain -> technicalTokenService.generate(openPaaSDomain.id()))
                .map(TechnicalTokenService.JwtToken::value)
                .block();
        }
    }

    private static final Domain DOMAIN = Domain.of("open-paas.ltd");
    private static final String TECHNICAL_TOKEN_INTROSPECT_PATH = "/api/technicalToken/introspect";

    @RegisterExtension
    @Order(1)
    private static final RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        BY_PASS_MODULE.apply(rabbitMQExtension),
        AppTestHelper.EVENT_BUS_BY_PASS_MODULE,
        DavModuleTestHelper.BY_PASS_MODULE,
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(TechnicalTokenProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {

        server.getProbe(CalendarDataProbe.class).addDomain(DOMAIN);

        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .build();
    }

    @Test
    void validTokenShouldReturnExpectedUserDetails(TwakeCalendarGuiceServer server) {
        String token = server.getProbe(TechnicalTokenProbe.class)
            .generateToken(DOMAIN);

        String response = RestAssured
            .given()
            .header("X-TECHNICAL-TOKEN", token)
        .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String technicalUserId = UUID.nameUUIDFromBytes(domainId.getBytes(StandardCharsets.UTF_8)).toString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "__v": 0,
                    "name": "Sabre Dav",
                    "domainId": "%s",
                    "_id": "%s",
                    "data": {
                        "principal": "principals/technicalUser"
                    },
                    "user_type": "technical",
                    "schemaVersion": 1,
                    "type": "dav",
                    "description": "Allows to authenticate on Sabre DAV"
                }""".formatted(domainId, technicalUserId));
    }

    @Test
    void tamperedTokenShouldReturn404(TwakeCalendarGuiceServer server) {
        String token = server.getProbe(TechnicalTokenProbe.class).generateToken(DOMAIN);
        String invalidToken = token + "x";

        String response = RestAssured
            .given()
            .header("X-TECHNICAL-TOKEN", invalidToken)
        .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not found",
                        "details": "Token not found or expired"
                    }
                }""");
    }

    @Test
    void malformedTokenShouldReturn404() {
        String invalidToken = "not-a-valid-jwt-token";

        String response = RestAssured.given()
            .header("X-TECHNICAL-TOKEN", invalidToken)
        .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not found",
                        "details": "Token not found or expired"
                    }
                }""");
    }

    @Test
    void shouldReturn404WhenMissingTokenInHeader() {
        String response = RestAssured.given()
            .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND)
            .contentType(JSON)
            .extract()
            .body()
            .asString();

        assertThatJson(response)
            .isEqualTo("""
                {
                    "error": {
                        "code": 404,
                        "message": "Not found",
                        "details": "Token not found or expired"
                    }
                }""");
    }

    @Test
    void tokensFromDifferentDomainsShouldReturnDifferentUserDetails(TwakeCalendarGuiceServer server) {
        String token1 = server.getProbe(TechnicalTokenProbe.class).generateToken(DOMAIN);
        String domainId1 = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();

        Function<String, String> getResponse = token -> RestAssured.given()
            .header("X-TECHNICAL-TOKEN", token)
        .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .asString();

        Domain otherDomain = Domain.of("other-domain.ltd");
        server.getProbe(CalendarDataProbe.class).addDomain(otherDomain);
        String domainId2 = server.getProbe(CalendarDataProbe.class).domainId(otherDomain).value();
        String token2 = server.getProbe(TechnicalTokenProbe.class).generateToken(otherDomain);

        Function<String, String> getTechnicalUserId = domainId -> UUID.nameUUIDFromBytes(domainId.getBytes(StandardCharsets.UTF_8)).toString();

        assertThatJson(getResponse.apply(token1))
            .isEqualTo("""
                {
                    "__v": 0,
                    "name": "Sabre Dav",
                    "domainId": "%s",
                    "_id": "%s",
                    "data": {
                        "principal": "principals/technicalUser"
                    },
                    "user_type": "technical",
                    "schemaVersion": 1,
                    "type": "dav",
                    "description": "Allows to authenticate on Sabre DAV"
                }""".formatted(domainId1, getTechnicalUserId.apply(domainId1)));

        assertThatJson(getResponse.apply(token2))
            .isEqualTo("""
                {
                    "__v": 0,
                    "name": "Sabre Dav",
                    "domainId": "%s",
                    "_id": "%s",
                    "data": {
                        "principal": "principals/technicalUser"
                    },
                    "user_type": "technical",
                    "schemaVersion": 1,
                    "type": "dav",
                    "description": "Allows to authenticate on Sabre DAV"
                }""".formatted(domainId2, getTechnicalUserId.apply(domainId2)));
    }

    @Test
    void sameValidTokenUsedMultipleTimesShouldReturnSameResponse(TwakeCalendarGuiceServer server) {
        String token = server.getProbe(TechnicalTokenProbe.class).generateToken(DOMAIN);

        Function<String, String> getResponse = tokenInput -> RestAssured.given()
            .header("X-TECHNICAL-TOKEN", token)
            .when()
            .get(TECHNICAL_TOKEN_INTROSPECT_PATH)
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .asString();

        String firstResponse = getResponse.apply(token);
        String secondResponse = getResponse.apply(token);

        assertThatJson(firstResponse)
            .isEqualTo(secondResponse);
    }
}
