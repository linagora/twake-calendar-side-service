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
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;

import jakarta.inject.Inject;

import org.apache.http.HttpStatus;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.DefaultCalendarPublicVisibility;
import com.linagora.calendar.storage.DomainSettings;
import com.linagora.calendar.storage.DomainSettingsDAO;
import com.linagora.calendar.storage.UserSearchMode;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class DomainSettingsRouteTest {

    record DomainSettingsProbe(DomainSettingsDAO domainSettingsDAO) implements GuiceProbe {
        @Inject
        DomainSettingsProbe {
        }

        void save(Domain domain, DomainSettings settings) {
            domainSettingsDAO.save(domain, settings).block();
        }
    }

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    @RegisterExtension
    @Order(1)
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        BY_PASS_MODULE.apply(rabbitMQExtension),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding()
            .to(DomainSettingsProbe.class));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(USERNAME, PASSWORD);

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(USERNAME.asString());
        auth.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(auth)
            .build();
    }

    @Test
    void getShouldReturnDefaultsWhenNoSettingsSaved() {
        given()
        .when()
            .get("/api/domain/settings")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("userSearchMode", equalTo("enabled"))
            .body("resourceSearchEnabled", equalTo(true))
            .body("defaultCalendarPublicVisibility", equalTo("private"));
    }

    @Test
    void getShouldReturn401WhenNotAuthenticated() {
        given()
            .auth().none()
        .when()
            .get("/api/domain/settings")
        .then()
            .statusCode(HttpStatus.SC_UNAUTHORIZED);
    }

    @Test
    void getShouldReturnSavedSettings(TwakeCalendarGuiceServer server) {
        DomainSettings settings = DomainSettings.builder()
            .userSearchMode(UserSearchMode.LIMITED)
            .resourceSearchEnabled(false)
            .defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility.READ)
            .build();
        server.getProbe(DomainSettingsProbe.class).save(Domain.of(DOMAIN), settings);

        given()
        .when()
            .get("/api/domain/settings")
        .then()
            .statusCode(HttpStatus.SC_OK)
            .body("userSearchMode", equalTo("limited"))
            .body("resourceSearchEnabled", equalTo(false))
            .body("defaultCalendarPublicVisibility", equalTo("read"));
    }
}
