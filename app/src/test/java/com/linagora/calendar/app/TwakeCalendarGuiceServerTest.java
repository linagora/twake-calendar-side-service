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

package com.linagora.calendar.app;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.with;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.jwt.introspection.IntrospectionEndpoint;
import org.apache.james.util.Port;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.WebAdminGuiceProbe;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import com.github.fge.lambdas.Throwing;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.modules.MemoryAutoCompleteModule;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import net.javacrumbs.jsonunit.core.Option;

class TwakeCalendarGuiceServerTest  {
    public static final Domain DOMAIN = Domain.of("linagora.com");
    public static final String PASSWORD = "secret";
    public static final Username USERNAME = Username.of("btellier@linagora.com");
    private static final String USERINFO_TOKEN_URI_PATH = "/token/userinfo";
    private static final String INTROSPECT_TOKEN_URI_PATH = "/oauth2/introspect";

    private static ClientAndServer mockServer = ClientAndServer.startClientAndServer(0);

    private static URL getUserInfoTokenEndpoint() {
        try {
            return new URI(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), USERINFO_TOKEN_URI_PATH)).toURL();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static URL getInrospectTokenEndpoint() {
        return Throwing.supplier(() -> URI.create(String.format("http://127.0.0.1:%s%s", mockServer.getLocalPort(), INTROSPECT_TOKEN_URI_PATH)).toURL()).get();
    }

    private OpenPaaSId userId;

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        DavModuleTestHelper.RABBITMQ_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        binder -> binder.bind(URL.class).annotatedWith(Names.named("userInfo")).toProvider(TwakeCalendarGuiceServerTest::getUserInfoTokenEndpoint),
        binder -> binder.bind(IntrospectionEndpoint.class).toProvider(() -> new IntrospectionEndpoint(getInrospectTokenEndpoint(), Optional.empty())),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(DomainAdminProbe.class));

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) {
        Port port = server.getProbe(WebAdminGuiceProbe.class).getWebAdminPort();

        RestAssured.requestSpecification =  new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(port.getValue())
            .setBasePath("/")
            .build();

        userId = server.getProbe(CalendarDataProbe.class)
            .addUser(USERNAME, PASSWORD);
    }

    @Test
    void shouldExposeWebAdminHealthcheck() {
        String body = given()
        .when()
            .get("/healthcheck")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body).withOptions(Option.IGNORING_ARRAY_ORDER).isEqualTo("""
            {
              "status" : "healthy",
              "checks" : [ {
                "componentName" : "Guice application lifecycle",
                "escapedComponentName" : "Guice%20application%20lifecycle",
                "status" : "healthy",
                "cause" : null
              }, {
                "componentName" : "RabbitMQDeadLetterQueueEmptiness",
                "escapedComponentName" : "RabbitMQDeadLetterQueueEmptiness",
                "status" : "healthy",
                "cause" : null
              }, {
                "componentName" : "CalendarQueueConsumers",
                "escapedComponentName" : "CalendarQueueConsumers",
                "status" : "healthy",
                "cause" : null
              }, {
                "componentName" : "RabbitMQ backend",
                "escapedComponentName" : "RabbitMQ%20backend",
                "status" : "healthy",
                "cause" : null
              } ]
            }
            """);
    }

    @Test
    void shouldExposeWebAdminDomains() {
        String body = given()
        .when()
            .get("/domains")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            ["linagora.com"]""");
    }

    @Test
    void shouldExposeWebAdminUsers() {
        String body = given()
        .when()
            .get("/users")
        .then()
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
            [{"username":"btellier@linagora.com"}]""");
    }

    @Test
    void shouldExposeWebAdminCalendarUsers() {
        String body = given()
        .when()
            .get("/registeredUsers")
        .then()
            .extract()
            .body()
            .asString();

        assertThat(body).contains("btellier@linagora.com");
    }

    @Test
    void shouldExposeWebAdminCalendarEventReindexTask() {
        String taskId = given()
            .when()
            .post("/calendars?task=reindex")
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
            .when()
            .get(taskId + "/await")
            .then()
            .body("status", is("failed"))
            .body("taskId", is(taskId))
            .body("type", is("reindex-calendar-events"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("reindex-calendar-events"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldExposeWebAdminCalendarDeleteUserDataRoutes(TwakeCalendarGuiceServer server) throws AddressException {
        String userId = "6053022c9da5ef001f430b43";
        String calendarId = "6053022c9da5ef001f430b43";
        String organizerEmail = "organizer@linagora.com";
        String attendeeEmail = "attendee@linagora.com";
        EventFields event = EventFields.builder()
            .uid("event-1")
            .summary("Title 1")
            .location("office")
            .description("note 1")
            .start(Instant.parse("2025-04-19T11:00:00Z"))
            .end(Instant.parse("2025-04-19T11:30:00Z"))
            .clazz("PUBLIC")
            .allDay(true)
            .isRecurrentMaster(true)
            .organizer(EventFields.Person.of("organizer", organizerEmail))
            .addAttendee(EventFields.Person.of("attendee", attendeeEmail))
            .addResource(new EventFields.Person("resource 1", new MailAddress("resource1@linagora.com")))
            .calendarURL(new CalendarURL(new OpenPaaSId(userId), new OpenPaaSId(calendarId)))
            .dtStamp(Instant.parse("2025-04-18T07:47:48Z"))
            .build();
        server.getProbe(CalendarDataProbe.class).indexCalendar(USERNAME, CalendarEvents.of(event));

        String taskId = with()
            .queryParam("action", "deleteData")
            .queryParam("fromStep", "CalendarSearchDeletionTaskStep")
            .post("/users/" + "btellier@linagora.com")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("type", is("DeleteUserDataTask"))
            .body("status", is("completed"))
            .body("additionalInformation.type", is("DeleteUserDataTask"))
            .body("additionalInformation.username", is("btellier@linagora.com"))
            .body("additionalInformation.status.DavCalendarDeletionTaskStep", is("SKIPPED"))
            .body("additionalInformation.status.DavContactDeletionTaskStep", is("SKIPPED"))
            .body("additionalInformation.status.CalendarSearchDeletionTaskStep", is("DONE"))
            .body("additionalInformation.status.OpenPaaSUserDeletionTaskStep", is("DONE"));

        assertThat(server.getProbe(CalendarDataProbe.class).searchEvents(USERNAME, "")).isEmpty();
        assertThat(server.getProbe(CalendarDataProbe.class).getUser(USERNAME)).isNull();
    }

    @Test
    void shouldExposeWebAdminAlarmScheduleTask() {
        String taskId = given()
            .when()
            .post("/calendars?task=scheduleAlarms")
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
            .when()
            .get(taskId + "/await")
            .then()
            .body("status", is("failed"))
            .body("taskId", is(taskId))
            .body("type", is("schedule-alarms"))
            .body("additionalInformation.processedEventCount", is(0))
            .body("additionalInformation.failedEventCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("schedule-alarms"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldExposeWebAdminRepositionResourceRightsTask() {
        String taskId = given()
            .when()
            .post("/resources?task=repositionWriteRights")
            .jsonPath()
            .get("taskId");

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is(notNullValue()))
            .body("taskId", is(taskId))
            .body("type", is("reposition-resource-rights"))
            .body("additionalInformation.processedResourceCount", is(notNullValue()))
            .body("additionalInformation.failedResourceCount", is(notNullValue()))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("reposition-resource-rights"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldExposeMetrics() {
        String body = given()
        .when()
            .get("/metrics")
        .then()
            .extract()
            .body()
            .asString();

        assertThat(body).contains("jvm_threads_runnable_count");
    }

    @Test
    void shouldExposeCalendarRestApi(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("{\"logos\":{},\"colors\":{}}");
    }

    @Test
    void shouldGenerateTokens(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .when()
            .post("/api/jwt/generate")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThat(body).startsWith("\"eyJ");
    }

    @Test
    void shouldAuthenticateWithTokens(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .when()
            .post("/api/jwt/generate")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();
        String unquoted = body.substring(1, body.length() - 1);

        given()
            .header("Authorization", "Bearer " + unquoted)
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldAuthenticateWithOidc(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String userInfoResponse = "{" +
            "  \"sub\": \"btellier\"," +
            "  \"email\": \"btellier@linagora.com\"," +
            "  \"family_name\": \"btellier\"," +
            "  \"sid\": \"dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c\"," +
            "  \"name\": \"btellier\"" +
            "}";
        updateMockerServerSpecifications(USERINFO_TOKEN_URI_PATH, userInfoResponse, 200);


        updateMockerServerSpecifications(INTROSPECT_TOKEN_URI_PATH, """
            {
                "exp": %d,
                "scope": "openid email profile",
                "client_id": "tcalendar",
                "active": true,
                "aud": "tcalendar",
                "sub": "twake-calendar-dev",
                "sid": "dT/8+UDx1lWp1bRZkdhbS1i6ZfYhf8+bWAZQs8p0T/c",
                "iss": "https://sso.linagora.com"
              }
              """.formatted(Clock.systemUTC().instant().plus(Duration.ofHours(1)).getEpochSecond()), 200);

        given()
            .header("Authorization", "Bearer oidc_opac_token")
        .when()
            .get("/api/themes/anything")
        .then()
            .statusCode(200);
    }

    @Test
    void shouldExposeLogoEndpoint(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .when()
            .get("/api/themes/anything/logo")
        .then()
            .statusCode(200); // Follows the redirect;
    }

    @Test
    void shouldRejectNonExistingUsers(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .auth().basic("notFound@linagora.com", PASSWORD)
            .when()
            .get("/api/themes/anything/logo")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldRejectBadPassword(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .auth().basic(USERNAME.asString(), "notGood")
            .when()
            .get("/api/themes/anything/logo")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldRejectProfileUpdate(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .auth().basic(USERNAME.asString(), "notGood")
        .when()
            .put("/api/user/profile")
        .then()
            .statusCode(405);
    }

    @Test
    void shouldRejectWhenNoAuth(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .when()
            .get("/api/themes/anything/logo")
        .then()
            .statusCode(401);
    }

    @Test
    void shouldServeDavConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"core\",\"keys\":[\"davserver\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [
    {"name":"core","configurations":[
     {"name":"davserver",
       "value":{
         "backend":{"url":"https://dav.linagora.com"},
         "frontend":{"url":"https://dav.linagora.com"}
        }
       }
      ]
     }
    ]""");
    }

    @Test
    void shouldServeHomePageConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"core\",\"keys\":[\"homePage\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"core","configurations":[{"name":"homePage","value": null}]}]""");
    }

    @Test
    void shouldServeAllowDomainAdminToManageUserEmailsConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"core\",\"keys\":[\"allowDomainAdminToManageUserEmails\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"core","configurations":[{"name":"allowDomainAdminToManageUserEmails","value": null}]}]""");
    }

    @Test
    void shouldServeAllowCalendarSharingConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"linagora.esn.calendar\",\"keys\":[\"features\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"linagora.esn.calendar","configurations":[{"name":"features","value":{"isSharingCalendarEnabled": true}}]}]""");
    }

    @Test
    void shouldServeAllowJitsiConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"linagora.esn.videoconference\",\"keys\":[\"jitsiInstanceUrl\",\"openPaasVideoconferenceAppUrl\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"linagora.esn.videoconference","configurations":[{"name":"jitsiInstanceUrl","value":"https://jitsi.linagora.com"},{"name":"openPaasVideoconferenceAppUrl","value":"https://jitsi.linagora.com"}]}]""");
    }

    @Test
    void shouldServeAllowContactsConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"linagora.esn.contact\",\"keys\":[\"features\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"linagora.esn.contact","configurations":[{"name":"features","value":{"isVirtualFollowingAddressbookEnabled":false,"isSharingAddressbookEnabled":true,"isVirtualUserAddressbookEnabled":false,"isDomainMembersAddressbookEnabled":true}}]}]""");
    }

    @Test
    void shouldServeLegacyCalendarConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"linagora.esn.calendar\",\"keys\":[\"hideDeclinedEvents\",\"workingDays\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"linagora.esn.calendar","configurations":[{"name":"hideDeclinedEvents","value":null},{"name":"workingDays", "value":null}]}]""");
    }

    @Test
    void shouldServeDefaultCoreConfiguration(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("[{\"name\":\"core\",\"keys\":[\"language\",\"businessHours\",\"datetime\"]}]")
        .when()
            .post("/api/configurations")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body).isEqualTo("""
    [{"name":"core","configurations":[{"name":"language","value":"en"},{"name":"businessHours","value":[{"start":"8:0","end":"19:0","daysOfWeek":[1,2,3,4,5]}]},{"name":"datetime","value":{"timeZone":"Europe/Paris","use24hourFormat":true}}]}]""");
    }

    @Test
    void shouldServeAvatars(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .queryParam("email", "btellier@linagora.com")
        .when()
            .get("/api/avatars")
        .then()
            .statusCode(200)
            .header("Content-Type", "image/png");
    }

    @Test
    void shouldSupportPeopleSearch(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        server.getProbe(MemoryAutoCompleteModule.Probe.class)
            .add(USERNAME.asString(), "grepme@linagora.com", "Grep", "Me");

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("{\"q\":\"grepm\",\"objectTypes\":[\"user\",\"group\",\"contact\",\"ldap\"],\"limit\":10}")
        .when()
            .post("/api/people/search")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("[0].id")
            .isEqualTo("""
    [{
      "id":"f0b97959-1f62-40f7-9df0-798125d62308",
      "objectType":"contact",
      "emailAddresses":[{"value":"grepme@linagora.com","type":"Work"}],
      "phoneNumbers":[],
      "names":[{"displayName":"Grep Me","type":"default"}],
      "photos":[{"url":"https://twcalendar.linagora.com/api/avatars?email=grepme@linagora.com","type":"default"}]
    }]""");
    }

    @Test
    void peopleSearchShouldIgnoreUnknownFields(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        server.getProbe(MemoryAutoCompleteModule.Probe.class)
            .add(USERNAME.asString(), "grepme@linagora.com", "Grep", "Me");

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .body("{\"q\":\"grepm\",\"objectTypes\":[\"user\",\"group\",\"contact\",\"ldap\"],\"limit\":10,\"unknown\":\"whatever\"}")
        .when()
            .post("/api/people/search")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("[0].id")
            .isEqualTo("""
    [{
      "id":"f0b97959-1f62-40f7-9df0-798125d62308",
      "objectType":"contact",
      "emailAddresses":[{"value":"grepme@linagora.com","type":"Work"}],
      "phoneNumbers":[],
      "names":[{"displayName":"Grep Me","type":"default"}],
      "photos":[{"url":"https://twcalendar.linagora.com/api/avatars?email=grepme@linagora.com","type":"default"}]
    }]""");
    }

    @Test
    void shouldSupportProfileAvatar(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        given()
            .redirects().follow(false)
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/users/" + userId.value() + "/profile/avatar")
        .then()
            .statusCode(302)
            .header("Location", "https://twcalendar.linagora.com/api/avatars?email=btellier@linagora.com");
    }

    @Test
    void shouldSupportDomainEndpoint(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        server.getProbe(DomainAdminProbe.class)
            .addAdmin(new OpenPaaSId(domainId), userId);

        String body = given()
            .redirects().follow(false)
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/domains/" + domainId)
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .isEqualTo(String.format("""
                {
                     "timestamps": {
                         "creation": "1970-01-01T00:00:00.000Z"
                     },
                     "hostnames": ["linagora.com"],
                     "schemaVersion": 1,
                     "_id": "%s",
                     "name": "linagora.com",
                     "company_name": "linagora.com",
                     "administrators": [
                          {
                              "user_id": "%s",
                              "timestamps": {
                                  "creation": "1970-01-01T00:00:00.000Z"
                              }
                          }
                     ],
                     "injections": [],
                     "__v": 0
                 }
                """, domainId, userId.value()));
    }

    @Test
    void domainEndpointShouldNotExposeDomainFromAnotherDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: two domains with one user each
        targetRestAPI(server);
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain domainA = Domain.of("domain-a.com");
        Domain domainB = Domain.of("domain-b.com");
        Username userDomainA = Username.fromLocalPartWithDomain("alice", domainA.asString());

        calendarDataProbe.addDomain(domainA).addUser(userDomainA, PASSWORD);
        calendarDataProbe.addDomain(domainB);

        String domainBId = calendarDataProbe.domainId(domainB).value();

        // WHEN: authenticated as domain A user, querying domain B
        given()
            .auth().preemptive().basic(userDomainA.asString(), PASSWORD)
        .when()
            .get("/api/domains/" + domainBId)
        // THEN: cross-domain access is blocked
        .then()
            .statusCode(404);
    }

    @Test
    void shouldSupportUserEndpoint(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String userId = server.getProbe(CalendarDataProbe.class).userId(USERNAME).value();
        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/users/" + userId)
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .isEqualTo(String.format("""
                {
                    "preferredEmail": "btellier@linagora.com",
                    "_id": "%s",
                    "state": [],
                    "domains": [
                        {
                            "domain_id": "%s",
                            "joined_at": "1970-01-01T00:00:00.000Z"
                        }
                    ],
                    "main_phone": "",
                    "followings": 0,
                    "following": false,
                    "followers": 0,
                    "emails": [
                        "btellier@linagora.com"
                    ],
                    "firstname": "btellier@linagora.com",
                    "lastname": "btellier@linagora.com",
                    "objectType": "user",
                    "timezone" : "Europe/Paris"
                }
                """, userId, domainId));
    }

    @Test
    void getUserByIdShouldNotExposeUserFromAnotherDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: two domains with one user each
        targetRestAPI(server);
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain domainA = Domain.of("domain-a.com");
        Domain domainB = Domain.of("domain-b.com");
        Username userDomainA = Username.fromLocalPartWithDomain("alice", domainA.asString());
        Username userDomainB = Username.fromLocalPartWithDomain("bob", domainB.asString());

        calendarDataProbe.addDomain(domainA).addUser(userDomainA, PASSWORD);
        calendarDataProbe.addDomain(domainB).addUser(userDomainB, PASSWORD);

        String userDomainBId = calendarDataProbe.userId(userDomainB).value();

        // WHEN: authenticated as domain A user, querying user B by id
        given()
            .auth().preemptive().basic(userDomainA.asString(), PASSWORD)
            .when()
        .get("/api/users/" + userDomainBId)
            .then()
            .statusCode(404);
    }

    @Test
    void shouldSupportUserByEmailEndpoint(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String userId = server.getProbe(CalendarDataProbe.class).userId(USERNAME).value();
        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .queryParam("email", "btellier@linagora.com")
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .isEqualTo(String.format("""
                [{
                    "preferredEmail": "btellier@linagora.com",
                    "_id": "%s",
                    "state": [],
                    "domains": [
                        {
                            "domain_id": "%s",
                            "joined_at": "1970-01-01T00:00:00.000Z"
                        }
                    ],
                    "main_phone": "",
                    "followings": 0,
                    "following": false,
                    "followers": 0,
                    "emails": [
                        "btellier@linagora.com"
                    ],
                    "firstname": "btellier@linagora.com",
                    "lastname": "btellier@linagora.com",
                    "objectType": "user",
                    "timezone" : "Europe/Paris"
                }]
                """, userId, domainId));
    }

    @Test
    void userByEmailEndpointShouldNotExposeUserFromAnotherDomain(TwakeCalendarGuiceServer server) {
        // GIVEN: two domains with one user each
        targetRestAPI(server);
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain domainA = Domain.of("domain-a.com");
        Domain domainB = Domain.of("domain-b.com");
        Username userDomainA = Username.fromLocalPartWithDomain("alice", domainA.asString());
        Username userDomainB = Username.fromLocalPartWithDomain("bob", domainB.asString());

        calendarDataProbe.addDomain(domainA).addUser(userDomainA, PASSWORD);
        calendarDataProbe.addDomain(domainB).addUser(userDomainB, PASSWORD);

        // WHEN: authenticated as domain A user, querying user from domain B by email
        String body = given()
            .auth().preemptive().basic(userDomainA.asString(), PASSWORD)
            .queryParam("email", userDomainB.asString())
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        // THEN: no cross-domain user information is leaked
        assertThatJson(body).isEqualTo("[]");
    }

    @Test
    void adminCanQueryUserAcrossDomainsByEmail(TwakeCalendarGuiceServer server) {
        // GIVEN: two domains with one user each
        targetRestAPI(server);
        CalendarDataProbe calendarDataProbe = server.getProbe(CalendarDataProbe.class);

        Domain domainB = Domain.of("domain-b.com");
        Username userDomainB = Username.fromLocalPartWithDomain("bob", domainB.asString());
        calendarDataProbe.addDomain(domainB).addUser(userDomainB, PASSWORD);

        // WHEN: admin queries user from another domain by email
        String adminUsername = "admin@linagora.com";
        String body = given()
            .auth().preemptive().basic(adminUsername, PASSWORD)
            .queryParam("email", userDomainB.asString())
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        // THEN: cross-domain access is allowed for admin
        assertThatJson(body)
            .isArray()
            .anySatisfy(json ->
                assertThatJson(json)
                    .node("preferredEmail")
                    .isEqualTo(userDomainB.asString()));
    }

    @Test
    void shouldSupportAdminCreds(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String userId = server.getProbe(CalendarDataProbe.class).userId(USERNAME).value();
        String body = given()
            .auth().preemptive().basic("admin@linagora.com", "secret")
            .queryParam("email", "btellier@linagora.com")
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .isEqualTo(String.format("""
                [{
                    "preferredEmail": "btellier@linagora.com",
                    "_id": "%s",
                    "state": [],
                    "domains": [
                        {
                            "domain_id": "%s",
                            "joined_at": "1970-01-01T00:00:00.000Z"
                        }
                    ],
                    "main_phone": "",
                    "followings": 0,
                    "following": false,
                    "followers": 0,
                    "emails": [
                        "btellier@linagora.com"
                    ],
                    "firstname": "btellier@linagora.com",
                    "lastname": "btellier@linagora.com",
                    "objectType": "user",
                    "timezone" : "Europe/Paris"
                }]
                """, userId, domainId));
    }

    @Test
    void userByEmailEndpointShouldReturnEmptyWHenNotFound(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .queryParam("email", "notFOund@linagora.com")
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .isEqualTo("[]");
    }

    @Test
    void userByEmailEndpointShouldProvisionMissingUsers(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);
        Username username = Username.of("to-be-provisionned@linagora.com");
        server.getProbe(CalendarDataProbe.class).addUserToRepository(username, "123456");
        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
            .queryParam("email", username.asString())
        .when()
            .get("/api/users")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();
        String userId = server.getProbe(CalendarDataProbe.class).userId(username).value();

        assertThatJson(body)
            .isEqualTo(String.format("""
                [{
                    "preferredEmail": "to-be-provisionned@linagora.com",
                    "_id": "%s",
                    "state": [],
                    "domains": [
                        {
                            "domain_id": "%s",
                            "joined_at": "1970-01-01T00:00:00.000Z"
                        }
                    ],
                    "main_phone": "",
                    "followings": 0,
                    "following": false,
                    "followers": 0,
                    "emails": [
                        "to-be-provisionned@linagora.com"
                    ],
                    "firstname": "to-be-provisionned@linagora.com",
                    "lastname": "to-be-provisionned@linagora.com",
                    "objectType": "user",
                    "timezone" : "Europe/Paris"
                }]
                """, userId, domainId));
    }

    @Test
    void getUserShouldReturnProfile(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);
        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String userId = server.getProbe(CalendarDataProbe.class).userId(USERNAME).value();

        String body = given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/user")
        .then()
            .statusCode(200)
            .extract()
            .body()
            .asString();

        assertThatJson(body)
            .whenIgnoringPaths("configurations.modules[4]")
            .isEqualTo(String.format("""
                {
                    "id": "%s",
                    "_id": "%s",
                    "accounts": [
                        {
                            "hosted": true,
                            "preferredEmailIndex": 0,
                            "type": "email",
                            "timestamps": {
                                "creation": "1970-01-01T00:00:00.000Z"
                            },
                            "emails": [
                                "btellier@linagora.com"
                            ]
                        }
                    ],
                    "isPlatformAdmin": false,
                    "login": {
                        "success": "1970-01-01T00:00:00.000Z",
                        "failures": []
                    },
                    "configurations": { "modules" : [
                        {
                            "name": "core",
                            "configurations": [
                                {
                                    "name": "davserver",
                                    "value": {
                                        "frontend": {
                                            "url": "https://dav.linagora.com"
                                        },
                                        "backend": {
                                            "url": "https://dav.linagora.com"
                                        }
                                    }
                                },
                                {
                                    "name": "allowDomainAdminToManageUserEmails",
                                    "value": null
                                },
                                {
                                    "name": "homePage",
                                    "value": null
                                },
                                {
                                    "name": "language",
                                    "value": "en"
                                },
                                {
                                    "name": "datetime",
                                    "value": {
                                        "timeZone": "Europe/Paris",
                                        "use24hourFormat": true
                                    }
                                },
                                {
                                    "name": "businessHours",
                                    "value": [
                                        {
                                            "start": "8:0",
                                            "end": "19:0",
                                            "daysOfWeek": [
                                                1,
                                                2,
                                                3,
                                                4,
                                                5
                                            ]
                                        }
                                    ]
                                }
                            ]
                        },
                        {
                            "name": "linagora.esn.calendar",
                            "configurations": [
                                {
                                    "name": "features",
                                    "value": {
                                        "isSharingCalendarEnabled": true
                                    }
                                },
                                {
                                    "name": "workingDays",
                                    "value": null
                                },
                                {
                                    "name": "hideDeclinedEvents",
                                    "value": null
                                }
                            ]
                        },
                        {
                            "name": "linagora.esn.videoconference",
                            "configurations": [
                                {
                                    "name": "jitsiInstanceUrl",
                                    "value": "https://jitsi.linagora.com"
                                },
                                {
                                    "name": "openPaasVideoconferenceAppUrl",
                                    "value": "https://jitsi.linagora.com"
                                }
                            ]
                        },
                        {
                            "name": "linagora.esn.contact",
                            "configurations": [
                                {
                                    "name": "features",
                                    "value": {
                                        "isVirtualFollowingAddressbookEnabled": false,
                                        "isVirtualUserAddressbookEnabled": false,
                                        "isSharingAddressbookEnabled": true,
                                        "isDomainMembersAddressbookEnabled": true
                                    }
                                }
                            ]
                        },
                        {
                            "configurations": [
                                {
                                    "name": "alarmEmails",
                                    "value": true
                                }, {
                                    "name":"displayWeekNumbers",
                                    "value":true
                                }
                            ],
                            "name": "calendar"
                        }
                    ]},
                    "preferredEmail": "btellier@linagora.com",
                    "state": [],
                    "domains": [
                        {
                            "domain_id": "%s",
                            "joined_at": "1970-01-01T00:00:00.000Z"
                        }
                    ],
                    "main_phone": "",
                    "followings": 0,
                    "following": false,
                    "followers": 0,
                    "emails": [
                        "btellier@linagora.com"
                    ],
                    "firstname": "btellier@linagora.com",
                    "lastname": "btellier@linagora.com",
                    "objectType": "user"
                }""", userId, userId, domainId));
    }

    @Test
    void getUserShouldAutoProvision(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);
        Username username = Username.of("to-be-provisionned@linagora.com");
        server.getProbe(CalendarDataProbe.class).addUserToRepository(username, "123456");

        given()
            .auth().preemptive().basic(username.asString(), "123456")
        .when()
            .get("/api/user")
        .then()
            .statusCode(200);
    }

    @Test
    void userShouldReturnNotFoundWhenNone(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();

        given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/users/" + domainId)
        .then()
            .statusCode(404);
    }

    @Test
    void domainShouldReturnNotFoundWhenNone(TwakeCalendarGuiceServer server) {
        targetRestAPI(server);

        String domainId = server.getProbe(CalendarDataProbe.class).domainId(DOMAIN).value();
        String userId = server.getProbe(CalendarDataProbe.class).userId(USERNAME).value();

        given()
            .auth().preemptive().basic(USERNAME.asString(), PASSWORD)
        .when()
            .get("/api/domains/" + userId)
        .then()
            .statusCode(404);
    }

    private static void targetRestAPI(TwakeCalendarGuiceServer server) {
        RestAssured.requestSpecification =  new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("/")
            .build();
    }

    private void updateMockerServerSpecifications(String path, String response, int statusResponse) {
        mockServer
            .when(HttpRequest.request().withPath(path))
            .respond(HttpResponse.response().withStatusCode(statusResponse)
                .withHeader("Content-Type", "application/json")
                .withBody(response, StandardCharsets.UTF_8));
    }
}
