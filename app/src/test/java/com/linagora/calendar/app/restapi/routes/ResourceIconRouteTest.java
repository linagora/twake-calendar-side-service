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

import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.restapi.RestApiServerProbe;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.response.Response;

class ResourceIconRouteTest {

    @RegisterExtension
    @Order(1)
    private static final RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        AppTestHelper.EVENT_BUS_BY_PASS_MODULE,
        AppTestHelper.BY_PASS_MODULE.apply(rabbitMQExtension));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        int restApiPort = server.getProbe(RestApiServerProbe.class).getPort().getValue();

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.ANY)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(restApiPort)
            .setBasePath("")
            .build();
    }

    @Test
    void shouldReturnSvgIconWhenExists() {
        Response response = given()
            .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        assertThat(response.getHeader("Content-Type")).isEqualTo("image/svg+xml");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=604800");
        assertThat(response.getHeader("ETag")).isNotBlank();

        String body = response.getBody().asString();
        String expectedSvg = """
            <?xml version="1.0" encoding="UTF-8"?><!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd"><svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" width="24" height="24" viewBox="0 0 24 24"><path d="M4,6H20V16H4M20,18A2,2 0 0,0 22,16V6C22,4.89 21.1,4 20,4H4C2.89,4 2,4.89 2,6V16A2,2 0 0,0 4,18H0V20H24V18H20Z" /></svg>""";

        assertThat(body.trim())
            .isEqualToIgnoringWhitespace(expectedSvg.trim());
    }

    @Test
    void shouldReturnNotFoundWhenIconDoesNotExist() {
        given()
            .when()
            .get("/linagora.esn.resource/images/icon/unknown.svg")
        .then()
            .statusCode(HttpStatus.SC_NOT_FOUND);
    }

    @Test
    void shouldReturnNotModifiedWhenEtagMatches() {
        Response firstResponse = given()
            .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        String etag = firstResponse.getHeader("ETag");

        given()
            .header("If-None-Match", etag)
        .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_NOT_MODIFIED);
    }

    @Test
    void shouldReturnOkWhenEtagDoesNotMatch() {
        Response firstResponse = given()
            .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .response();

        assertThat(firstResponse.getHeader("ETag")).isNotBlank();
        byte[] expectedBody = firstResponse.getBody().asByteArray();

        given()
            .header("If-None-Match", "\"some-random-etag\"")
        .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body(equalTo(new String(expectedBody, StandardCharsets.UTF_8)));
    }

    @Test
    void shouldReturnNotModifiedWhenIfNoneMatchStarAndIconExists() {
        given()
            .header("If-None-Match", "*")
        .when()
            .get("/linagora.esn.resource/images/icon/laptop.svg")
        .then()
            .statusCode(HttpStatus.SC_NOT_MODIFIED);
    }

    @Test
    void shouldReturnBadRequestWhenIconParamMissing() {
        given()
            .when()
            .get("/linagora.esn.resource/images/icon/.svg")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }
}