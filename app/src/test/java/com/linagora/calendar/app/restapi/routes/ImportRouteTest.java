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
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.mail.internet.MimeUtility;

import org.apache.http.HttpStatus;
import org.apache.james.core.MaybeSender;
import org.apache.james.util.Port;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedMimeType;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.CalendarComponent;

public class ImportRouteTest {

    private static final String PASSWORD = "secret";

    ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await();

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    public static Function<MockSmtpServerExtension, MailSenderConfiguration> mailSenderConfigurationFunction = mockSmtpExtension -> new MailSenderConfiguration(
        "localhost",
        Port.of(mockSmtpExtension.getMockSmtp().getSmtpPort()),
        "localhost",
        Optional.empty(),
        Optional.empty(),
        false,
        false,
        false);

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> binder.bind(MailTemplateConfiguration.class)
            .toInstance(new MailTemplateConfiguration("classpath://templates/",
                MaybeSender.getMailSender("no-reply@openpaas.org"))),
        binder -> binder.bind(MailSenderConfiguration.class)
            .toInstance(mailSenderConfigurationFunction.apply(mockSmtpExtension)));


    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setup(TwakeCalendarGuiceServer server) {
        this.openPaaSUser = sabreDavExtension.newTestUser();

        server.getProbe(CalendarDataProbe.class).addDomain(openPaaSUser.username().getDomainPart().get());
        server.getProbe(CalendarDataProbe.class).addUserToRepository(openPaaSUser.username(), PASSWORD);

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(openPaaSUser.username().asString());
        auth.setPassword(PASSWORD);

        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setAuth(auth)
            .setBasePath("")
            .setAccept(ContentType.JSON)
            .setContentType(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .build();

        given(mockSMTPRequestSpecification())
            .delete("/smtpMails")
            .then();

        given(mockSMTPRequestSpecification())
            .delete("/smtpBehaviors")
            .then();
    }

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    @Test
    void shouldImportSuccessfully(TwakeCalendarGuiceServer server) {
        String uid = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test
            DESCRIPTION:This is an automatic alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .formatted(uid, openPaaSUser.username().asString(), openPaaSUser.username().asString(), openPaaSUser.username().asString())
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("abc.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
            {
                "fileId": "%s",
                "target": "/calendars/%s/%s.json"
            }
            """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // To trigger calendar directory activation
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Calendar actual = CalendarUtil.parseIcs(
                    server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username())));

                assertThat(actual.getComponent(Component.VEVENT).get().getProperty(Property.UID).get().getValue()).isEqualTo(uid);
            });
    }

    @Test
    void shouldReportMailWhenImportEventSucceed(TwakeCalendarGuiceServer server) {
        String uid = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test
            DESCRIPTION:This is an automatic alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .formatted(uid, openPaaSUser.username().asString(), openPaaSUser.username().asString(), openPaaSUser.username().asString())
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("abc.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
            {
                "fileId": "%s",
                "target": "/calendars/%s/%s.json"
            }
            """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // To trigger calendar directory activation
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
            .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        Supplier<JsonPath> smtpMailsResponseSupplier  = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat( smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath  smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(openPaaSUser.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Calendar import reporting")
                .contains("Content-Transfer-Encoding: base64", "Content-Type: text/html; charset=UTF-8")  // text HTML body part
                .contains("Content-Disposition: inline; filename=\"logo.png\"",
                    "Content-ID: logo",
                    "Content-Transfer-Encoding: base64",
                    "Content-Type: image/png; name=\"logo.png\""); // base64 encoded image
        }));
    }

    private void setUserLanguage(Locale locale) {
        given()
            .body("""
            [
              {
                "name": "core",
                "configurations": [
                  {
                    "name": "language",
                    "value": "%s"
                  }
                ]
              }
            ]
            """.formatted(locale.getLanguage()))
        .when()
            .put("/api/configurations?scope=user")
        .then()
            .statusCode(HttpStatus.SC_NO_CONTENT);

    }

    @Test
    void shouldReportMailWithI18NWhenImportEventSucceed(TwakeCalendarGuiceServer server) {
        // Given set language to France
        setUserLanguage(Locale.FRANCE);

        String uid = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test
            DESCRIPTION:This is an automatic alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .formatted(uid, openPaaSUser.username().asString(), openPaaSUser.username().asString(), openPaaSUser.username().asString())
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("abc.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
            {
                "fileId": "%s",
                "target": "/calendars/%s/%s.json"
            }
            """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // To trigger calendar directory activation
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
            .when()
            .post("/api/import")
            .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        Supplier<JsonPath> smtpMailsResponseSupplier  = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat( smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath  smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(openPaaSUser.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Rapport d'importation de calendrier");
        }));
    }

    @Test
    void shouldReportMailDefaultToEnglishWhenLanguageTemplateIsMissing(TwakeCalendarGuiceServer server) {
        // Given: user sets language to Japanese (which has no template)
        setUserLanguage(Locale.JAPAN);

        String uid = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Test Event
            ORGANIZER;CN=john doe:mailto:%s
            ATTENDEE;PARTSTAT=accepted;RSVP=false;ROLE=chair;CUTYPE=individual:mailto:%s
            DESCRIPTION:This is a test event
            LOCATION:office
            CLASS:PUBLIC
            BEGIN:VALARM
            TRIGGER:-PT5M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:test
            DESCRIPTION:This is an automatic alarm
            END:VALARM
            END:VEVENT
            END:VCALENDAR
            """
            .formatted(uid, openPaaSUser.username().asString(), openPaaSUser.username().asString(), openPaaSUser.username().asString())
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("abc.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
            {
                "fileId": "%s",
                "target": "/calendars/%s/%s.json"
            }
            """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // Ensure calendar directory is activated
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat(smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(openPaaSUser.username().asString());

            // Fallback to English subject
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Calendar import reporting"); // English fallback
        });
    }


    @Test
    void shouldImportMultipleEventsSuccessfully(TwakeCalendarGuiceServer server) {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        byte[] ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:First Event
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250103T120000Z
            DTEND:20250103T130000Z
            SUMMARY:Second Event
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1, uid2).getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("multi.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/calendars/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // To trigger calendar directory activation
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Calendar actual = CalendarUtil.parseIcs(server.getProbe(CalendarDataProbe.class)
                    .exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username())));

                assertThat(actual.getComponents(Component.VEVENT))
                    .anySatisfy(component -> assertThat(((CalendarComponent) component).getProperty(Property.UID).get().getValue()).isEqualTo(uid1))
                    .anySatisfy(component -> assertThat(((CalendarComponent) component).getProperty(Property.UID).get().getValue()).isEqualTo(uid2));
            });
    }

    @Test
    void shouldImportRecurringEventsSuccessfully(TwakeCalendarGuiceServer server) {
        String uid1 = UUID.randomUUID().toString();
        String uid2 = UUID.randomUUID().toString();
        String username = openPaaSUser.username().asString();
        byte[] ics = """
            BEGIN:VCALENDAR
            VERSION:2.0
            CALSCALE:GREGORIAN
            PRODID:-//SabreDAV//SabreDAV 3.2.2//EN
            X-WR-CALNAME:#default
            BEGIN:VTIMEZONE
            TZID:Asia/Jakarta
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:WIB
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250514T130000
            DTEND;TZID=Asia/Saigon:20250514T133000
            CLASS:PUBLIC
            SUMMARY:recur222
            RRULE:FREQ=DAILY;COUNT=3
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T073930Z
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250515T120000
            DTEND;TZID=Asia/Saigon:20250515T123000
            CLASS:PUBLIC
            SUMMARY:recur222
            ORGANIZER;CN=John1 Doe1:mailto:%s
            DTSTAMP:20250515T073930Z
            RECURRENCE-ID:20250515T060000Z
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=John1
              Doe1:mailto:%s
            SEQUENCE:1
            END:VEVENT
            BEGIN:VEVENT
            UID:%s
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Saigon:20250513T140000
            DTEND;TZID=Asia/Saigon:20250513T143000
            CLASS:PUBLIC
            SUMMARY:test555
            ORGANIZER;CN=John1 Doe1:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:%s
            DTSTAMP:20250515T074016Z
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1, username, username, uid1, username, username, uid2, username, username)
            .getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("multi.ics", UploadedMimeType.TEXT_CALENDAR, Instant.now(), (long) ics.length, ics));

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/calendars/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), openPaaSUser.id().value());

        // To trigger calendar directory activation
        server.getProbe(CalendarDataProbe.class).exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()));

        given()
            .body(requestBody)
            .when()
            .post("/api/import")
            .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> {
                Calendar actual = CalendarUtil.parseIcs(server.getProbe(CalendarDataProbe.class)
                    .exportCalendarFromCalDav(new CalendarURL(openPaaSUser.id(), openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username())));

                assertThat(actual.getComponents(Component.VEVENT))
                    .anySatisfy(component -> {
                        assertThat(((CalendarComponent) component).getProperty(Property.UID).get().getValue()).isEqualTo(uid1);
                        assertThat(((CalendarComponent) component).getProperty(Property.RRULE)).isNotEmpty();
                    })
                    .anySatisfy(component -> {
                        assertThat(((CalendarComponent) component).getProperty(Property.UID).get().getValue()).isEqualTo(uid1);
                        assertThat(((CalendarComponent) component).getProperty(Property.RECURRENCE_ID)).isNotEmpty();
                    })
                    .anySatisfy(component -> assertThat(((CalendarComponent) component).getProperty(Property.UID).get().getValue()).isEqualTo(uid2));
            });
    }

    @Test
    void shouldImportVcardSuccessfully(TwakeCalendarGuiceServer server) {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        byte[] vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("contact.vcf", UploadedMimeType.TEXT_VCARD, Instant.now(), (long) vcard.length, vcard));

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/addressbooks/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), addressBook);

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .ignoreExceptions()
            .untilAsserted(() -> {
                String contact = new String(server.getProbe(CalendarDataProbe.class)
                    .exportContactFromCardDav(openPaaSUser.username(), openPaaSUser.id(), addressBook),
                    StandardCharsets.UTF_8);
                assertThat(contact).contains("EMAIL;TYPE=Work:john.doe@example.com");
            });
    }

    @Test
    void shouldReportMailWhenImportVcardSucceed(TwakeCalendarGuiceServer server) {
        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        byte[] vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("contact.vcf", UploadedMimeType.TEXT_VCARD, Instant.now(), (long) vcard.length, vcard));

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/addressbooks/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), addressBook);

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
            .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        Supplier<JsonPath> smtpMailsResponseSupplier  = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat( smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath  smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(openPaaSUser.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: Contacts import reporting")
                .contains("Content-Transfer-Encoding: base64",
                    "Content-Type: text/html; charset=UTF-8") // text HTML body part
                .contains("Content-Disposition: inline; filename=\"logo.png\"",
                    "Content-ID: logo",
                    "Content-Transfer-Encoding: base64",
                    "Content-Type: image/png; name=\"logo.png\""); // base64 encoded image
        }));
    }

    @Test
    void shouldReportMailWithI18NWhenImportVcardSucceed(TwakeCalendarGuiceServer server) {
        // Given set language to VI
        setUserLanguage(Locale.of("vi"));

        String addressBook = "collected";
        String vcardUid = UUID.randomUUID().toString();
        byte[] vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            """.formatted(vcardUid).getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("contact.vcf", UploadedMimeType.TEXT_VCARD, Instant.now(), (long) vcard.length, vcard));

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/addressbooks/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), addressBook);

        given()
            .body(requestBody)
            .when()
            .post("/api/import")
            .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        Supplier<JsonPath> smtpMailsResponseSupplier  = () -> given(mockSMTPRequestSpecification())
            .get("/smtpMails")
            .jsonPath();

        CALMLY_AWAIT
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> assertThat( smtpMailsResponseSupplier.get().getList("")).hasSize(1));

        JsonPath  smtpMailsResponse = smtpMailsResponseSupplier.get();

        assertSoftly(Throwing.consumer(softly -> {
            softly.assertThat(smtpMailsResponse.getString("[0].from")).isEqualTo("no-reply@openpaas.org");
            softly.assertThat(smtpMailsResponse.getString("[0].recipients[0].address")).isEqualTo(openPaaSUser.username().asString());
            softly.assertThat(smtpMailsResponse.getString("[0].message"))
                .contains("Subject: "+ MimeUtility.encodeText("Báo cáo nhập danh bạ", "UTF-8", "B"));
        }));
    }

    @Test
    void shouldImportMultipleVcardsSuccessfully(TwakeCalendarGuiceServer server) {
        String addressBook = "collected";
        String vcardUid1 = UUID.randomUUID().toString();
        String vcardUid2 = UUID.randomUUID().toString();
        byte[] vcard = """
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe
            EMAIL;TYPE=Work:john.doe@example.com
            END:VCARD
            BEGIN:VCARD
            VERSION:4.0
            UID:%s
            FN:John Doe 2
            EMAIL;TYPE=Work:john.doe2@example.com
            END:VCARD
            """.formatted(vcardUid1, vcardUid2).getBytes(StandardCharsets.UTF_8);

        OpenPaaSId fileId = server.getProbe(CalendarDataProbe.class).saveUploadedFile(openPaaSUser.username(),
            new Upload("contacts.vcf", UploadedMimeType.TEXT_VCARD, Instant.now(), (long) vcard.length, vcard));

        // To trigger address book activation
        server.getProbe(CalendarDataProbe.class).exportContactFromCardDav(openPaaSUser.username(), openPaaSUser.id(), addressBook);

        String requestBody = """
        {
            "fileId": "%s",
            "target": "/addressbooks/%s/%s.json"
        }
        """.formatted(fileId.value(), openPaaSUser.id().value(), addressBook);

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_ACCEPTED);

        CALMLY_AWAIT.atMost(Duration.ofSeconds(10))
            .ignoreExceptions()
            .untilAsserted(() -> {
                String contact = new String(server.getProbe(CalendarDataProbe.class)
                    .exportContactFromCardDav(openPaaSUser.username(), openPaaSUser.id(), addressBook),
                    StandardCharsets.UTF_8);
                assertThat(contact).contains("EMAIL;TYPE=Work:john.doe@example.com");
                assertThat(contact).contains("EMAIL;TYPE=Work:john.doe2@example.com");
            });
    }

    @Test
    void shouldReturnErrorWhenFileIdDoesNotExist() {
        String requestBody = """
            {
                "fileId": "659387b9d486dc0046aeff21",
                "target": "/calendars/%s/calendarId.json"
            }
            """.formatted(openPaaSUser.id().value());

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Uploaded file not found"));
    }

    @Test
    void shouldReturnErrorWhenTargetIsInvalid() {
        String requestBody = """
        {
            "fileId": "659387b9d486dc0046aeff21",
            "target": "/invalid/path"
        }
        """;

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("Invalid target path"));
    }

    @Test
    void shouldReturnErrorWhenFileIdFieldIsMissing() {
        String requestBody = """
            {
                "target": "/calendars/%s/calendarId.json"
            }
            """.formatted(openPaaSUser.id().value());

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("fileId must be present"));
    }

    @Test
    void shouldReturnErrorWhenTargetFieldIsMissing() {
        String requestBody = """
            {
                "fileId": "659387b9d486dc0046aeff21"
            }
            """;

        given()
            .body(requestBody)
        .when()
            .post("/api/import")
        .then()
            .statusCode(HttpStatus.SC_BAD_REQUEST)
            .body("error.code", equalTo(400))
            .body("error.message", equalTo("Bad request"))
            .body("error.details", equalTo("target must be present"));
    }
}

