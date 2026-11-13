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
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.io.ByteArrayInputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.Delay;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration;
import com.linagora.calendar.saas.drive.configuration.TDriveConfiguration.InstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.LocalPartTDriveInstanceResolver;
import com.linagora.calendar.saas.drive.tdrive.TDriveAttachmentStore;
import com.linagora.calendar.storage.FileUploadConfiguration;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HeaderConfig;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.MultiPartConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

class AttachmentUploadRouteTest {
    private static final String DOMAIN = "example.com";
    private static final Username USERNAME = Username.of("alice.smith@" + DOMAIN);
    private static final String PASSWORD = "secret";
    private static final int MAX_FILE_SIZE = 1024; // 1 KiB
    private static final byte[] CONTENT = Arrays.copyOf(new byte[] {0, 1, 2, 10, 13, 127, (byte) 128, (byte) 255, 3, 4, 5, 6}, MAX_FILE_SIZE);
    private static final String UPLOAD_PATH = "/api/attachments";
    private static final String DRIVE_DOMAIN = "drive.example.com";
    private static final String TOKEN_PATH = "/instances/alicesmith.drive.example.com/token";
    private static final String FILE_PATH = "/alicesmith.drive.example.com/files/folder-id";
    private static final String PERMISSION_PATH = "/alicesmith.drive.example.com/permissions";
    private static final String PUBLIC_URI = "https://alicesmith-drive.drive.example.com/public?sharecode=public-code#/";
    private static final String TOKEN_JSON = """
        {"token": "service-secret"}
        """;
    private static final ClientAndServer drive = ClientAndServer.startClientAndServer(0);

    @RegisterExtension
    @Order(1)
    private static final RabbitMQExtension rabbitMQ = RabbitMQExtension.singletonRabbitMQ().isolationPolicy(WEAK);

    @AfterAll
    static void stopDrive() {
        drive.stop();
    }

    private static TwakeCalendarExtension extension(boolean enabled) {
        TwakeCalendarConfiguration.Builder builder = TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY);
        if (enabled) {
            builder.enableTDrive();
        }
        return new TwakeCalendarExtension(builder,
            AppTestHelper.BY_PASS_MODULE.apply(rabbitMQ),
            binder -> binder.bind(FileUploadConfiguration.class).toInstance(
                new FileUploadConfiguration(FileUploadConfiguration.DEFAULT_EXPIRATION, (long) MAX_FILE_SIZE)),
            binder -> {
                if (enabled) {
                    String baseUrl = "http://localhost:" + drive.getLocalPort();
                    TDriveConfiguration configuration = new TDriveConfiguration(true, Optional.of(DRIVE_DOMAIN),
                        baseUrl + "/instances/{fqdn}/token",
                        "partner-secret",
                        "Calendar attachments",
                        "https://{instanceLabel}-drive.{instanceSuffix}/public?sharecode={sharecode}#/",
                        Duration.ofSeconds(2), false, Optional.empty(), InstanceResolver.LOCAL_PART);
                    binder.bind(TDriveConfiguration.class).toInstance(configuration);
                    binder.bind(TDriveAttachmentStore.class).toInstance(new TDriveAttachmentStore(configuration,
                        new LocalPartTDriveInstanceResolver(configuration.localPartDomain()), uri -> URI.create(baseUrl + "/" + uri.getHost())));
                } else {
                    binder.bind(RestApiConfiguration.class).toInstance(fallbackProxyToDriveMock());
                }
            });
    }

    private static RestApiConfiguration fallbackProxyToDriveMock() {
        try {
            return RestApiConfiguration.builder()
                .enableBasicAuth(Optional.of(true))
                .adminPassword(Optional.of(PASSWORD))
                .openpaasBackendURL(Optional.of(URI.create("http://localhost:" + drive.getLocalPort()).toURL()))
                .build();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private void setUp(TwakeCalendarGuiceServer server) {
        drive.reset();
        RestAssured.reset();
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(USERNAME, PASSWORD);
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setConfig(RestAssuredConfig.config()
                .headerConfig(HeaderConfig.headerConfig().overwriteHeadersWithName("X-Filename")))
            .setContentType("application/pdf")
            .addHeader("X-Filename", "r%C3%A9sum%C3%A9.pdf")
            .setAccept(ContentType.JSON)
            .build();
    }

    private Response postAttachment(byte[] content) {
        return postAttachment(content, PASSWORD);
    }

    private Response postAttachment(byte[] content, String password) {
        return given()
            .auth().preemptive().basic(USERNAME.asString(), password)
            .body(content)
        .when()
            .post(UPLOAD_PATH);
    }

    private void assertNoStorageRequests() {
        assertThat(drive.retrieveRecordedRequests(request())).isEmpty();
    }

    @Nested
    class Enabled {
        @RegisterExtension
        @Order(2)
        final TwakeCalendarExtension calendar = extension(true);

        @BeforeEach
        void setUpDrive(TwakeCalendarGuiceServer server) {
            setUp(server);

            drive.when(request().withMethod("POST").withPath(TOKEN_PATH))
                .respond(response().withStatusCode(200).withBody(TOKEN_JSON));
            drive.when(request().withMethod("GET").withPath("/alicesmith.drive.example.com/files/metadata"))
                .respond(response().withStatusCode(200).withBody("""
                    {
                      "data": {
                        "id": "folder-id",
                        "attributes": {"type": "directory"}
                      }
                    }
                    """));
            drive.when(request().withMethod("POST").withPath(FILE_PATH))
                .respond(response().withStatusCode(201).withBody("""
                    {
                      "data": {
                        "id": "file-id",
                        "attributes": {"type": "file"}
                      }
                    }
                    """));
            drive.when(request().withMethod("POST").withPath(PERMISSION_PATH))
                .respond(response().withStatusCode(200).withBody("""
                    {
                      "data": {
                        "attributes": {
                          "shortcodes": {"calendar": "public-code"}
                        }
                      }
                    }
                    """));
        }

        @ParameterizedTest
        @ValueSource(strings = {"résumé.pdf", "résumé +%.pdf", "文件.bin"})
        void shouldUploadAsAuthenticatedUserAndReturnPublicUriAtSizeLimit(String filename) {
            String body = given()
                .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
                .header("X-Filename", URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"))
                .body(CONTENT)
            .when()
                .post(UPLOAD_PATH)
            .then()
                .statusCode(201)
                .contentType(ContentType.JSON)
                .extract().asString();

            assertThatJson(body).isEqualTo("""
                {"uri": "<uri>"}
                """.replace("<uri>", PUBLIC_URI));

            var tokenRequests = drive.retrieveRecordedRequests(request().withPath(TOKEN_PATH));
            assertThat(tokenRequests).hasSize(1);
            assertThat(tokenRequests[0].getFirstHeader("Authorization")).isEqualTo("Bearer partner-secret");

            var uploads = drive.retrieveRecordedRequests(request().withPath(FILE_PATH));
            assertThat(uploads).hasSize(1);
            assertThat(uploads[0].getBodyAsRawBytes()).isEqualTo(CONTENT);
            assertThat(uploads[0].getFirstHeader("Content-Type")).isEqualTo("application/pdf");
            assertThat(uploads[0].getFirstHeader("Authorization")).isEqualTo("Bearer service-secret");
            assertThat(uploads[0].getFirstQueryStringParameter("Name")).isEqualTo(filename);

            assertThat(drive.retrieveRecordedRequests(request().withPath(PERMISSION_PATH))).hasSize(1);
        }

        @Nested
        class Multipart {
            private RequestSpecification authenticatedRequest() {
                return given().auth().preemptive().basic(USERNAME.asString(), PASSWORD);
            }

            @BeforeEach
            void useMultipartRequests(TwakeCalendarGuiceServer server) {
                // No X-Filename header: the filename comes from the multipart file part.
                RestAssured.requestSpecification = new RequestSpecBuilder()
                    .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
                    .setContentType("multipart/form-data")
                    .setConfig(RestAssuredConfig.config()
                        .httpClient(HttpClientConfig.httpClientConfig().httpMultipartMode(HttpMultipartMode.BROWSER_COMPATIBLE))
                        .multiPartConfig(MultiPartConfig.multiPartConfig().defaultCharset(StandardCharsets.UTF_8)))
                    .build();
            }

            @Test
            void shouldUploadBinaryAtFileSizeLimit() {
                String filename = "résumé +%.pdf";
                String body = authenticatedRequest()
                    .multiPart("file", filename, CONTENT, "application/pdf")
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(201)
                    .contentType(ContentType.JSON)
                    .extract().asString();

                assertThatJson(body).isEqualTo("""
                    {"uri": "<uri>"}
                    """.replace("<uri>", PUBLIC_URI));
                assertThat(drive.retrieveRecordedRequests(request().withPath(FILE_PATH)))
                    .singleElement().satisfies(upload -> {
                        assertThat(upload.getBodyAsRawBytes()).isEqualTo(CONTENT);
                        assertThat(upload.getFirstHeader("Content-Type")).isEqualTo("application/pdf");
                        assertThat(upload.getFirstQueryStringParameter("Name")).isEqualTo(filename);
                    });
            }

            @Test
            void shouldDefaultMissingPartContentTypeToBinary() {
                authenticatedRequest()
                    .contentType("multipart/form-data; boundary=\"upload\"")
                    .body(("--upload\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.bin\"\r\n\r\n"
                        + "data\r\n--upload--\r\n").getBytes(StandardCharsets.UTF_8))
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(201);

                assertThat(drive.retrieveRecordedRequests(request().withPath(FILE_PATH)))
                    .singleElement().satisfies(upload -> {
                        assertThat(upload.getBodyAsRawBytes()).isEqualTo("data".getBytes(StandardCharsets.UTF_8));
                        assertThat(upload.getFirstHeader("Content-Type")).isEqualTo("application/octet-stream");
                    });
            }

            @Test
            void shouldRejectMultipleFiles() {
                authenticatedRequest()
                    .multiPart("file", "one.pdf", CONTENT, "application/pdf")
                    .multiPart("file", "two.pdf", CONTENT, "application/pdf")
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(400);

                assertNoStorageRequests();
            }

            @Test
            void shouldRequireFileField() {
                authenticatedRequest()
                    .multiPart("other", "report.pdf", CONTENT, "application/pdf")
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(400);

                assertNoStorageRequests();
            }

            @Test
            void shouldRejectTextFieldWithoutFilename() {
                authenticatedRequest()
                    .multiPart("file", "text")
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(400);

                assertNoStorageRequests();
            }

            @ParameterizedTest
            @ValueSource(strings = {"", "--upload--\r\n",
                "--upload\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\ndata"})
            void shouldRejectEmptyOrIncompleteMultipart(String body) {
                authenticatedRequest()
                    .contentType("multipart/form-data; boundary=upload")
                    .body(body.getBytes(StandardCharsets.UTF_8))
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(400);

                assertNoStorageRequests();
            }

            @Test
            void shouldFollowNettyForMissingClosingBoundary() {
                // receiveForm emits the completed file at the next boundary, even without a closing boundary.
                authenticatedRequest()
                    .contentType("multipart/form-data; boundary=upload")
                    .body(("--upload\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n\r\n"
                        + "data\r\n--upload\r\n").getBytes(StandardCharsets.UTF_8))
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(201);

                assertThat(drive.retrieveRecordedRequests(request().withPath(FILE_PATH)))
                    .singleElement().satisfies(upload -> {
                        assertThat(upload.getFirstQueryStringParameter("Name")).isEqualTo("a.txt");
                        assertThat(upload.getBodyAsRawBytes()).isEqualTo("data".getBytes(StandardCharsets.UTF_8));
                    });
            }

            @ParameterizedTest
            @ValueSource(strings = {"multipart/form-data", "multipart/form-data; boundary=\"\"",
                "multipart/form-data; boundary=one; boundary=two"})
            void shouldRejectMissingOrAmbiguousBoundary(String contentType) {
                authenticatedRequest()
                    .contentType(contentType)
                    .body("invalid multipart".getBytes(StandardCharsets.UTF_8))
                .when()
                    .post(UPLOAD_PATH)
                .then()
                    .statusCode(400);

                assertNoStorageRequests();
            }

            @Test
            void shouldRejectOversizedChunkedUpload(TwakeCalendarGuiceServer server) throws Exception {
                int size = MAX_FILE_SIZE + 1;
                byte[] header = ("--upload\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.pdf\"\r\n"
                    + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                byte[] footer = "\r\n--upload--\r\n".getBytes(StandardCharsets.UTF_8);
                byte[] body = new byte[header.length + size + footer.length];
                System.arraycopy(header, 0, body, 0, header.length);
                System.arraycopy(footer, 0, body, header.length + size, footer.length);
                String auth = Base64.getEncoder().encodeToString((USERNAME.asString() + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
                int port = server.getProbe(RestApiServerProbe.class).getPort().getValue();
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + UPLOAD_PATH))
                    .header("Authorization", "Basic " + auth)
                    .header("Content-Type", "multipart/form-data; boundary=upload")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                    .build();

                try (HttpClient client = HttpClient.newHttpClient()) {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(413);
                }
                assertNoStorageRequests();
            }
        }

        @Test
        void shouldRequireAuthentication() {
            given()
                .body(CONTENT)
            .when()
                .post(UPLOAD_PATH)
            .then()
                .statusCode(401);

            assertNoStorageRequests();
        }

        @Test
        void shouldRejectWrongCredentials() {
            postAttachment(CONTENT, "wrong-password")
            .then()
                .statusCode(401);

            assertNoStorageRequests();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "%20", "../a.txt", "%2Fprivate.txt", "a%5Cb.txt", "%00", "%0D%0A", "%GG", "%", ".", ".."})
        void shouldRejectInvalidFilenameBeforeCallingStorage(String filename) {
            given()
                .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
                .header("X-Filename", filename)
                .body(CONTENT)
            .when()
                .post(UPLOAD_PATH)
            .then()
                .statusCode(400);

            assertNoStorageRequests();
        }

        @Test
        void shouldRequireFilename(TwakeCalendarGuiceServer server) {
            RestAssured.reset();
            RestAssured.requestSpecification = new RequestSpecBuilder()
                .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
                .build();

            given()
                .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
                .contentType("application/pdf")
                .body(CONTENT)
            .when()
                .post(UPLOAD_PATH)
            .then()
                .statusCode(400);

            assertNoStorageRequests();
        }

        @Test
        void shouldRejectContentOverLimit() {
            postAttachment(new byte[MAX_FILE_SIZE + 1])
            .then()
                .statusCode(413);

            assertNoStorageRequests();
        }

        @Test
        void shouldEnforceLimitOnChunkedRequests(TwakeCalendarGuiceServer server) throws Exception {
            byte[] body = new byte[MAX_FILE_SIZE + 1];
            String credentials = USERNAME.asString() + ":" + PASSWORD;
            String auth = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
            int port = server.getProbe(RestApiServerProbe.class).getPort().getValue();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + UPLOAD_PATH))
                .header("Authorization", "Basic " + auth)
                .header("Content-Type", "application/pdf")
                .header("X-Filename", "file.pdf")
                // The unknown content length makes the client send a chunked request.
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)))
                .build();

            try (HttpClient client = HttpClient.newHttpClient()) {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                assertThat(response.statusCode()).isEqualTo(413);
            }

            assertNoStorageRequests();
        }

        @ParameterizedTest
        @ValueSource(ints = {401, 403, 503})
        void shouldReturnBadGatewayForUpstreamFailures(int status) {
            drive.clear(request().withPath(TOKEN_PATH));
            drive.when(request().withPath(TOKEN_PATH))
                .respond(response().withStatusCode(status));

            postAttachment(CONTENT)
            .then()
                .statusCode(502);

            assertThat(drive.retrieveRecordedRequests(request())).hasSize(1);
        }

        @Test
        void shouldReturnGatewayTimeout() {
            drive.clear(request().withPath(TOKEN_PATH));
            drive.when(request().withPath(TOKEN_PATH))
                .respond(response().withDelay(Delay.seconds(3)).withBody(TOKEN_JSON));

            postAttachment(CONTENT)
            .then()
                .statusCode(504);

            assertThat(drive.retrieveRecordedRequests(request())).hasSize(1);
        }
    }

    @Nested
    class Disabled {
        @RegisterExtension
        @Order(2)
        final TwakeCalendarExtension calendar = extension(false);

        @BeforeEach
        void setUpCalendar(TwakeCalendarGuiceServer server) {
            setUp(server);
        }

        @Test
        void shouldReturnNotFoundForMultipartWhenTDriveIsDisabled() {
            given()
                .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
                .contentType("multipart/form-data")
                .multiPart("file", "report.pdf", CONTENT, "application/pdf")
            .when()
                .post(UPLOAD_PATH)
            .then()
                .statusCode(404);

            assertNoStorageRequests();
        }

        @Test
        void shouldReturnNotFoundWhenTDriveIsDisabled() {
            postAttachment(CONTENT)
            .then()
                .statusCode(404);

            assertNoStorageRequests();
        }
    }
}
