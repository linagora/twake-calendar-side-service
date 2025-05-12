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
import static org.hamcrest.Matchers.equalTo;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

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
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.FileUploadConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadableMimeType;
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
        DavModuleTestHelper.BY_PASS_MODULE,
        binder -> binder.bind(FileUploadConfiguration.class).toProvider(() ->
            new FileUploadConfiguration(FileUploadConfiguration.DEFAULT_EXPIRATION, 3L * 1024 * 1024)));

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
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
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
        assertThat(file.uploadableMimeType()).isEqualTo(UploadableMimeType.TEXT_CALENDAR);
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
    void shouldRejectWhenNameIsMissing() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        given()
            .queryParam("size", content.length)
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectWhenSizeIsMissing() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        given()
            .queryParam("name", "data.txt")
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectWhenSizeIsInvalid() {
        byte[] content = "data".getBytes(StandardCharsets.UTF_8);

        given()
            .queryParam("name", "data.txt")
            .queryParam("size", "invalid")
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST);
    }

    @Test
    void shouldRejectWhenActualSizeIsLargerThanDeclared() {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8); // 10 bytes
        int declaredSize = 5;

        given()
            .queryParam("name", "oversize.txt")
            .queryParam("size", declaredSize)
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(content)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Real size is greater than declared size"));
    }

    @Test
    void shouldDeleteOldFilesWhenOverUserLimit(TwakeCalendarGuiceServer server) {
        byte[] small = new byte[1024 * 1024]; // 1MB
        Arrays.fill(small, (byte) 'c');

        Instant now = Instant.now();

        OpenPaaSId old1Id = server.getProbe(CalendarDataProbe.class).saveUploadedFile(USERNAME,
            new Upload("old1.txt", UploadableMimeType.TEXT_CALENDAR, now, (long) small.length, small));

        OpenPaaSId old2Id = server.getProbe(CalendarDataProbe.class).saveUploadedFile(USERNAME,
            new Upload("old2.txt", UploadableMimeType.TEXT_CALENDAR, now.plusSeconds(10), (long) small.length, small));

        OpenPaaSId old3Id = server.getProbe(CalendarDataProbe.class).saveUploadedFile(USERNAME,
            new Upload("old3.txt", UploadableMimeType.TEXT_CALENDAR, now.plusSeconds(20), (long) small.length, small));

        byte[] newFile = new byte[2 * 1024 * 1024];  // 2MB
        Arrays.fill(newFile, (byte) 'c');

        String newFileId = given()
            .queryParam("name", "new.txt")
            .queryParam("size", newFile.length)
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(newFile)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract()
            .body()
            .jsonPath()
            .getString("_id");

        List<UploadedFile> files = server.getProbe(CalendarDataProbe.class).listUploadedFiles(USERNAME);
        assertThat(files).hasSize(2);

        assertThat(files).anySatisfy(file -> {
            assertThat(file.id().value()).isEqualTo(newFileId);
            assertThat(file.fileName()).isEqualTo("new.txt");
        });

        assertThat(files).anySatisfy(file -> {
            assertThat(file.id()).isEqualTo(old3Id);
            assertThat(file.fileName()).isEqualTo("old3.txt");
        });
    }

    @Test
    void shouldNotDeleteAnyFileWhenEnoughSpace(TwakeCalendarGuiceServer server) {
        byte[] small = new byte[1024 * 1024]; // 1MB
        Arrays.fill(small, (byte) 'c');

        Instant now = Instant.now();

        OpenPaaSId old1Id = server.getProbe(CalendarDataProbe.class).saveUploadedFile(USERNAME,
            new Upload("old1.txt", UploadableMimeType.TEXT_CALENDAR, now, (long) small.length, small));

        OpenPaaSId old2Id = server.getProbe(CalendarDataProbe.class).saveUploadedFile(USERNAME,
            new Upload("old2.txt", UploadableMimeType.TEXT_CALENDAR, now.plusSeconds(10), (long) small.length, small));

        byte[] newFile = new byte[1024 * 1024]; // 1MB
        Arrays.fill(newFile, (byte) 'c');

        String newFileId = given()
            .queryParam("name", "new.txt")
            .queryParam("size", newFile.length)
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(newFile)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_CREATED)
            .extract()
            .body()
            .jsonPath()
            .getString("_id");

        List<UploadedFile> files = server.getProbe(CalendarDataProbe.class).listUploadedFiles(USERNAME);
        assertThat(files).hasSize(3);

        assertThat(files).anySatisfy(file -> {
            assertThat(file.id().value()).isEqualTo(newFileId);
            assertThat(file.fileName()).isEqualTo("new.txt");
        });

        assertThat(files).anyMatch(file -> file.id().equals(old1Id));
        assertThat(files).anyMatch(file -> file.id().equals(old2Id));
    }

    @Test
    void shouldRejectUploadWhenFileSizeExceedsUserLimit(TwakeCalendarGuiceServer server) {
        byte[] tooBigFile = new byte[4 * 1024 * 1024]; // 4MB
        Arrays.fill(tooBigFile, (byte) 'c');

        given()
            .queryParam("name", "too-big.txt")
            .queryParam("size", tooBigFile.length)
            .queryParam("mimetype", UploadableMimeType.TEXT_CALENDAR.getType())
            .body(tooBigFile)
        .when()
            .post("/api/files")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("File size exceeds user total upload limit"));
    }
}

