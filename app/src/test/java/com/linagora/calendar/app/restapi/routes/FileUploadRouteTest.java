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
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import org.apache.http.HttpStatus;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.inject.name.Names;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.MimeType;
import com.linagora.calendar.storage.model.UploadedFile;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

public class FileUploadRouteTest {

    private static final String DOMAIN = "open-paas.ltd";
    private static final String PASSWORD = "secret";
    private static final Username USERNAME = Username.fromLocalPartWithDomain("bob", DOMAIN);

    @RegisterExtension
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo"))
            .toProvider(() -> Throwing.supplier(() -> new URI("https://neven.to.be.called.com").toURL()).get())
    );

    @BeforeEach
    void setup(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(USERNAME, PASSWORD);

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(USERNAME.asString());
        auth.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setAuth(auth)
            .setBasePath("")
            .setAccept(ContentType.JSON)
            .setContentType(ContentType.BINARY)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .build();
    }

    @Test
    void shouldUploadFileSuccessfully(TwakeCalendarGuiceServer server) {
        byte[] content = "BEGIN:VCALENDAR\nVERSION:2.0\nEND:VCALENDAR".getBytes(StandardCharsets.UTF_8);

        String id = given()
            .queryParam("name", "calendar.ics")
            .queryParam("size", content.length)
            .queryParam("mimetype", MimeType.TEXT_CALENDAR.getType())
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .contentType(ContentType.JSON)
            .extract()
            .body()
            .jsonPath()
            .getString("_id");

        UploadedFile file = server.getProbe(CalendarDataProbe.class).getUploadedFile(USERNAME, new OpenPaaSId(id));
        assertThat(file.fileName()).isEqualTo("calendar.ics");
        assertThat(file.mimeType()).isEqualTo(MimeType.TEXT_CALENDAR);
        assertThat(file.size()).isEqualTo(content.length);
        assertThat(file.data()).isEqualTo(content);
    }

    @Test
    void shouldRejectWhenMimeTypeIsMissing() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        given()
            .queryParam("name", "data.txt")
            .queryParam("size", content.length)
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectWhenMimeTypeIsUnknown() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        given()
            .queryParam("name", "data.txt")
            .queryParam("size", content.length)
            .queryParam("mimetype", "text/unknown")
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRemoveOldFilesIfNeeded(TwakeCalendarGuiceServer server) {
    }
}

