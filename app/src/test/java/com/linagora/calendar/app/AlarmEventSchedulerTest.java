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

import static com.linagora.calendar.app.restapi.routes.ImportRouteTest.mailSenderConfigurationFunction;
import static com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration.BATCH_SIZE_DEFAULT;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.james.core.MailAddress;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.inject.Inject;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.Fixture;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration;
import com.linagora.calendar.smtp.MailSenderConfiguration;
import com.linagora.calendar.smtp.MockSmtpServerExtension;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.eventsearch.EventUid;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public class AlarmEventSchedulerTest {
    private static final Duration TRIGGER = Duration.ofMinutes(15);

    static class AlarmEventStoreProbe implements GuiceProbe {
        private final AlarmEventDAO alarmEventDAO;

        @Inject
        AlarmEventStoreProbe(AlarmEventDAO alarmEventDAO) {
            this.alarmEventDAO = alarmEventDAO;
        }

        public Optional<AlarmEvent> find(String eventUid, String recipient) {
            return alarmEventDAO.find(new EventUid(eventUid),
                Throwing.supplier(() -> new MailAddress(recipient)).get()).blockOptional();
        }
    }

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @RegisterExtension
    @Order(2)
    static final MockSmtpServerExtension mockSmtpExtension = new MockSmtpServerExtension();

    static UpdatableTickingClock clock = new UpdatableTickingClock(Instant.now());

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension),
        binder -> {
            binder.bind(AlarmEventSchedulerConfiguration.class)
                .toInstance(new AlarmEventSchedulerConfiguration(
                    Duration.ofSeconds(1),  // pollInterval
                    BATCH_SIZE_DEFAULT,
                    Duration.ofMillis(100), // jitter
                    AlarmEventSchedulerConfiguration.Mode.CLUSTER));

            binder.bind(MailSenderConfiguration.class)
                .toInstance(mailSenderConfigurationFunction.apply(mockSmtpExtension));
            binder.bind(Clock.class).toInstance(clock);
            Multibinder.newSetBinder(binder, GuiceProbe.class)
                .addBinding().to(AlarmEventStoreProbe.class);
        });

    static RequestSpecification mockSMTPRequestSpecification() {
        return new RequestSpecBuilder()
            .setPort(mockSmtpExtension.getMockSmtp().getRestApiPort())
            .setBasePath("")
            .build();
    }

    private OpenPaaSUser organizer;
    private DavTestHelper davTestHelper;
    private CalDavEventRepository calDavEventRepository;


    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        this.organizer = sabreDavExtension.newTestUser(Optional.of("organizer_"));
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration());
        calDavEventRepository = new CalDavEventRepository(new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration()));

        clearSmtpInbox();

        given(mockSMTPRequestSpecification())
            .delete("/smtpBehaviors")
            .then();
    }

    private void clearSmtpInbox() {
        given(mockSMTPRequestSpecification())
            .delete("/smtpMails")
            .then();
    }

    @Test
    void shouldSendEmailAlarmAtTrigger(TwakeCalendarGuiceServer server) {
        AlarmEventStoreProbe alarmStore = server.getProbe(AlarmEventStoreProbe.class);

        String eventUid = UUID.randomUUID().toString();
        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".trim().formatted(organizer.username().asString());
        String calendarData = generateEventWithVALARM(
            eventUid,
            organizer.username().asString(), List.of(),
            vAlarm);

        // No alarm persisted before upsert
        assertThat(alarmStore.find(eventUid, organizer.username().asString())).isEmpty();

        // Given
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(alarmStore.find(eventUid, organizer.username().asString())).isPresent());

        clearSmtpInbox();

        // Advance time to after trigger window starts (start - 15m + 1s)
        Calendar calendar = CalendarUtil.parseIcs(calendarData);
        ZonedDateTime start = EventParseUtils.getStartTime((VEvent) calendar.getComponents("VEVENT").getFirst());
        clock.setInstant(start.minus(TRIGGER).plusSeconds(1).toInstant());

        Supplier<JsonPath> smtpMailsResponseSupplier = () -> given(mockSMTPRequestSpecification()).get("/smtpMails").jsonPath();

        Fixture.awaitAtMost.untilAsserted(() -> {
            JsonPath jp = smtpMailsResponseSupplier.get();
            assertThat(jp.getList("")).hasSize(1);
            String organizerAddr = organizer.username().asString();
            String path = "find { it.from == 'no-reply@openpaas.org' && it.recipients && it.recipients[0].address == '" + organizerAddr + "' }";
            assertThat(jp.getString(path + ".from")).isEqualTo("no-reply@openpaas.org");
            assertThat(jp.getString(path + ".recipients[0].address")).isEqualTo(organizerAddr);
            assertThat(jp.getString(path + ".message"))
                .contains("Subject: Notification: Twake Calendar - Sprint planning #04");
        });

        Fixture.awaitAtMost.untilAsserted(() ->
            assertThat(alarmStore.find(eventUid, organizer.username().asString())).isEmpty());
    }

    @Test
    void shouldSendEmailOnlyToAcceptedAttendees(TwakeCalendarGuiceServer server) {
        AlarmEventStoreProbe alarmStore = server.getProbe(AlarmEventStoreProbe.class);

        // Given
        EventUid eventUid = new EventUid(UUID.randomUUID().toString());

        // Three attendees
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser(Optional.of("attendee1_"));
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser(Optional.of("attendee2_"));
        OpenPaaSUser attendee3 = sabreDavExtension.newTestUser(Optional.of("attendee3_"));

        String vAlarm = """
            BEGIN:VALARM
            TRIGGER:-PT15M
            ACTION:EMAIL
            ATTENDEE:mailto:%s
            SUMMARY:Test multi attendees
            DESCRIPTION:This is an automatic alarm sent by OpenPaas
            END:VALARM""".formatted(organizer.username().asString());

        // Event ICS with 3 attendees
        String calendarData = generateEventWithVALARM(
            eventUid.value(),
            organizer.username().asString(),
            List.of(attendee1.username().asString(),
                attendee2.username().asString(),
                attendee3.username().asString()),
            vAlarm);

        // Insert event to DAV server
        davTestHelper.upsertCalendar(organizer, calendarData, eventUid);

        // Update attendee participation status
        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThatCode(() -> {
                calDavEventRepository.updatePartStat(attendee1.username(), attendee1.id(), eventUid, PartStat.ACCEPTED).block();
                calDavEventRepository.updatePartStat(attendee2.username(), attendee2.id(), eventUid, PartStat.ACCEPTED).block();
                calDavEventRepository.updatePartStat(attendee3.username(), attendee3.id(), eventUid, PartStat.DECLINED).block();
            }).doesNotThrowAnyException();
        });

        // Then (DB state before trigger)
        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThat(alarmStore.find(eventUid.value(), attendee1.username().asString())).isPresent();
            assertThat(alarmStore.find(eventUid.value(), attendee2.username().asString())).isPresent();
            assertThat(alarmStore.find(eventUid.value(), attendee3.username().asString())).isEmpty(); // DECLINED
        });

        // When (time advances to trigger window)
        clearSmtpInbox();
        Calendar calendar = CalendarUtil.parseIcs(calendarData);
        ZonedDateTime start = EventParseUtils.getStartTime((VEvent) calendar.getComponents("VEVENT").getFirst());
        clock.setInstant(start.minus(TRIGGER).plusSeconds(1).toInstant());

        Supplier<JsonPath> smtpMailsResponseSupplier =
            () -> given(mockSMTPRequestSpecification()).get("/smtpMails").jsonPath();

        // Then (emails should be sent only to accepted attendees)
        String alarmEmailSubjectExpected = "Subject: Notification: Twake Calendar - Sprint planning #04";
        Fixture.awaitAtMost.untilAsserted(() -> {
            JsonPath jp = smtpMailsResponseSupplier.get();

            // Attendee1
            assertThat(jp.getString("find { it.recipients[0].address == '" + attendee1.username().asString() + "' }" + ".message"))
                .contains(alarmEmailSubjectExpected);

            // Attendee2
            assertThat(jp.getString("find { it.recipients[0].address == '" + attendee2.username().asString() + "' }" + ".message"))
                .contains(alarmEmailSubjectExpected);

            // Ensure attendee3 did not receive any mail
            String path3 = "find { it.recipients[0].address == '" + attendee3.username().asString() + "' }";
            assertThat(jp.getString(path3)).isNull();
        });

        // Then (cleanup: alarms removed after sending)
        Fixture.awaitAtMost.untilAsserted(() -> {
            assertThat(alarmStore.find(eventUid.value(), attendee1.username().asString())).isEmpty();
            assertThat(alarmStore.find(eventUid.value(), attendee2.username().asString())).isEmpty();
        });
    }


    private String generateEventWithVALARM(String eventUid,
                                           String organizerEmail,
                                           List<String> attendeeEmails,
                                           String vAlarm) {
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
        LocalDateTime baseTime = clock.instant().atZone(clock.getZone()).plusDays(3).toLocalDateTime();
        String startDateTime = baseTime.format(dateTimeFormatter);
        String endDateTime = baseTime.plusHours(1).format(dateTimeFormatter);
        String dtStamp = baseTime.minusDays(3).format(dateTimeFormatter);
        String attendeeLines = attendeeEmails.stream()
            .map(email -> String.format("ATTENDEE;PARTSTAT=%s:mailto:%s", PartStat.NEEDS_ACTION.getValue(), email))
            .collect(Collectors.joining("\n"));
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.2.2//EN
            CALSCALE:GREGORIAN
            BEGIN:VTIMEZONE
            TZID:Asia/Ho_Chi_Minh
            BEGIN:STANDARD
            TZOFFSETFROM:+0700
            TZOFFSETTO:+0700
            TZNAME:ICT
            DTSTART:19700101T000000
            END:STANDARD
            END:VTIMEZONE
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:%sZ
            SEQUENCE:1
            DTSTART;TZID=Asia/Ho_Chi_Minh:%s
            DTEND;TZID=Asia/Ho_Chi_Minh:%s
            SUMMARY:Twake Calendar - Sprint planning #04
            ORGANIZER:mailto:%s
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:%s
            %s
            %s
            END:VEVENT
            END:VCALENDAR
            """.formatted(
            eventUid,
            dtStamp,
            startDateTime,
            endDateTime,
            organizerEmail,
            organizerEmail,
            attendeeLines,
            vAlarm);
    }

}
