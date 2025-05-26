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

package com.linagora.calendar.dav.amqp;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.james.backends.rabbitmq.Constants.EMPTY_ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.backends.rabbitmq.QueueArguments;
import org.apache.james.backends.rabbitmq.RabbitMQConfiguration;
import org.apache.james.backends.rabbitmq.RabbitMQConnectionFactory;
import org.apache.james.backends.rabbitmq.ReactorRabbitMQChannelPool;
import org.apache.james.backends.rabbitmq.SimpleConnectionPool;
import org.apache.james.core.Username;
import org.apache.james.metrics.api.NoopGaugeRegistry;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.vacation.api.AccountId;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.testcontainers.shaded.org.awaitility.Awaitility;
import org.testcontainers.shaded.org.awaitility.core.ConditionFactory;

import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventFields;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;
import com.linagora.calendar.storage.eventsearch.EventUid;
import com.linagora.calendar.storage.eventsearch.MemoryCalendarSearchService;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.rabbitmq.OutboundMessage;
import reactor.rabbitmq.QueueSpecification;
import reactor.rabbitmq.Sender;

public class DavCalendarEventConsumerTest {
    private final ConditionFactory calmlyAwait = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .and()
        .with()
        .pollDelay(Duration.ofMillis(500))
        .await();
    private final ConditionFactory awaitAtMost = calmlyAwait.atMost(200, TimeUnit.SECONDS);

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static ReactorRabbitMQChannelPool channelPool;
    private static SimpleConnectionPool connectionPool;
    private static DavTestHelper davTestHelper;

    @BeforeAll
    static void beforeAll(DockerSabreDavSetup dockerSabreDavSetup) throws Exception {
        RabbitMQConfiguration rabbitMQConfiguration = dockerSabreDavSetup.rabbitMQConfiguration();

        RabbitMQConnectionFactory connectionFactory = new RabbitMQConnectionFactory(rabbitMQConfiguration);
        connectionPool = new SimpleConnectionPool(connectionFactory,
            SimpleConnectionPool.Configuration.builder()
                .retries(2)
                .initialDelay(Duration.ofMillis(5)));
        channelPool = new ReactorRabbitMQChannelPool(connectionPool.getResilientConnection(),
            ReactorRabbitMQChannelPool.Configuration.builder()
                .retries(2)
                .maxBorrowDelay(Duration.ofMillis(250))
                .maxChannel(10),
            new RecordingMetricFactory(),
            new NoopGaugeRegistry());
        channelPool.start();

        davTestHelper = new DavTestHelper(dockerSabreDavSetup.davConfiguration());
    }

    @AfterAll
    static void afterAll() {
        channelPool.close();
        connectionPool.close();
    }

    private OpenPaaSUser openPaasUser;
    private CalendarSearchService calendarSearchService;
    private Sender sender;

    @BeforeEach
    public void setUp(DockerSabreDavSetup dockerSabreDavSetup) {
        openPaasUser = sabreDavExtension.newTestUser();
        calendarSearchService = Mockito.spy(new MemoryCalendarSearchService());

        MongoDatabase mongoDB = dockerSabreDavSetup.getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        OpenPaaSUserDAO openPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);

        DavCalendarEventConsumer calendarEventConsumer = new DavCalendarEventConsumer(channelPool, calendarSearchService, openPaaSUserDAO,
            QueueArguments.Builder::new);
        calendarEventConsumer.init();

        sender = channelPool.getSender();
    }

    @AfterEach
    void afterEach() {
        List.of("tcalendar:event:created",
                "tcalendar:event:updated",
                "tcalendar:event:deleted",
                "tcalendar:event:cancel",
                "tcalendar:event:request")
            .forEach(queueName -> sender.delete(QueueSpecification.queue()
                    .name(queueName))
                .block());

        Mockito.reset(calendarSearchService);
    }

    @Test
    void shouldIndexEventInSearchEngineForOrganizerWhenCreated() {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = getSampleCalendar(eventUid);
        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);
    }

    @Test
    void shouldIndexAllCalendarFields() throws AddressException {
        String eventUid = UUID.randomUUID().toString();
        String summary = "Meeting Summary";
        String description = "Detailed meeting description";
        String location = "Meeting Room 101";
        String organizer = openPaasUser.username().asString();
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee3 = sabreDavExtension.newTestUser();

        String calendarData =
            """
                BEGIN:VCALENDAR\r
                VERSION:2.0\r
                CALSCALE:GREGORIAN\r
                PRODID:-//SabreDAV//SabreDAV 3.2.2//EN\r
                X-WR-CALNAME:#default\r
                BEGIN:VTIMEZONE\r
                TZID:Asia/Jakarta\r
                BEGIN:STANDARD\r
                TZOFFSETFROM:+0700\r
                TZOFFSETTO:+0700\r
                TZNAME:WIB\r
                DTSTART:19700101T000000\r
                END:STANDARD\r
                END:VTIMEZONE\r
                BEGIN:VEVENT\r
                UID:{eventUid}\r
                TRANSP:OPAQUE\r
                DTSTART;VALUE=DATE:20250512\r
                DTEND;VALUE=DATE:20250515\r
                CLASS:PUBLIC\r
                SUMMARY:{summary}\r
                LOCATION:{location}\r
                DESCRIPTION:{description}\r
                ORGANIZER;CN=John1 Doe1:mailto:{organizer}\r
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=INDIVIDUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.1:\
                mailto:{attendee1}\r
                ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=INDIVIDUAL;CN=John3 Doe3;SCHEDULE-STATUS=1.1:\
                mailto:{attendee2}\r
                ATTENDEE;PARTSTAT=TENTATIVE;RSVP=TRUE;ROLE=REQ-PARTICIPANT;\
                CUTYPE=RESOURCE;CN=Test resource;SCHEDULE-STATUS=5.1:\
                mailto:{attendee3}\r
                ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;\
                CUTYPE=INDIVIDUAL:mailto:{organizer}\r
                DTSTAMP:20250515T091619Z\r
                BEGIN:VALARM\r
                TRIGGER:-PT30M\r
                ACTION:EMAIL\r
                ATTENDEE:mailto:{organizer}\r
                SUMMARY:Full field calendar\r
                DESCRIPTION:This is an automatic alarm sent by OpenPaas\\nThe event Full field calendar will start 4 days ago\\nstart: Mon May 12 2025 00:00:00 GMT+0700 \\nend: Thu May 15 2025 00:00:00 GMT+0700 \\nlocation: Location 1 \\nclass: PUBLIC \\n\r
                END:VALARM\r
                END:VEVENT\r
                END:VCALENDAR\r
                """.replace("{eventUid}", eventUid)
                .replace("{summary}", summary)
                .replace("{description}", description)
                .replace("{location}", location)
                .replace("{organizer}", organizer)
                .replace("{attendee1}", attendee1.username().asString())
                .replace("{attendee2}", attendee2.username().asString())
                .replace("{attendee3}", attendee3.username().asString());


        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        assertEventExistsInSearch(openPaasUser.username(), summary, eventUid);

        EventFields eventFields = calendarSearchService.search(AccountId.fromUsername(openPaasUser.username()), simpleQuery(summary))
            .next()
            .block();

        assertThat(eventFields)
            .isEqualTo(EventFields.builder()
                .calendarURL(new CalendarURL(new OpenPaaSId(openPaasUser.id().value()), new OpenPaaSId(openPaasUser.id().value())))
                .uid(new EventUid(eventUid))
                .summary(summary)
                .location(location)
                .description(description)
                .clazz("PUBLIC")
                .start(Instant.parse("2025-05-12T00:00:00Z"))
                .end(Instant.parse("2025-05-15T00:00:00Z"))
                .dtStamp(Instant.parse("2025-05-15T09:16:19Z"))
                .allDay(true)
                .organizer(EventFields.Person.of("John1 Doe1", openPaasUser.username().asString()))
                .addAttendee(EventFields.Person.of("John2 Doe2", attendee1.username().asString()))
                .addAttendee(EventFields.Person.of("John3 Doe3", attendee2.username().asString()))
                .addAttendee(EventFields.Person.of(null, openPaasUser.username().asString()))
                .addResource(EventFields.Person.of("Test resource", attendee3.username().asString()))
                .build());
    }

    @Test
    void shouldIndexAllCalendarFieldsWhenRecurrenceEvent() throws AddressException {
        String eventUid = UUID.randomUUID().toString();
        String summary = "Meeting Summary";
        String description = "Detailed meeting description";
        String location = "Meeting Room 101";
        String organizer = openPaasUser.username().asString();
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();

        String calendarData = """
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
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250515T113000
            DTEND;TZID=Asia/Jakarta:20250515T120000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            RRULE:FREQ=WEEKLY;COUNT=14;BYDAY=FR
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.1:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:{organizer}
            DTSTAMP:20250516T060320Z
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250523T143000
            DTEND;TZID=Asia/Jakarta:20250523T150000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            DTSTAMP:20250516T060320Z
            RECURRENCE-ID:20250523T043000Z
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=John1
              Doe1:mailto:{organizer}
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{summary}", summary)
            .replace("{description}", description)
            .replace("{location}", location)
            .replace("{organizer}", organizer)
            .replace("{attendee1}", attendee1.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        assertEventExistsInSearch(openPaasUser.username(), summary, eventUid);

        List<EventFields> eventFields = calendarSearchService.search(AccountId.fromUsername(openPaasUser.username()), simpleQuery(summary))
            .collectList()
            .block();

        EventFields masterEvent = EventFields.builder()
            .calendarURL(new CalendarURL(new OpenPaaSId(openPaasUser.id().value()), new OpenPaaSId(openPaasUser.id().value())))
            .uid(new EventUid(eventUid))
            .isRecurrentMaster(true)
            .summary(summary)
            .location(location)
            .description(description)
            .clazz("PUBLIC")
            .start(Instant.parse("2025-05-15T04:30:00Z"))
            .end(Instant.parse("2025-05-15T05:00:00Z"))
            .dtStamp(Instant.parse("2025-05-16T06:03:20Z"))
            .allDay(false)
            .organizer(EventFields.Person.of("John1 Doe1", openPaasUser.username().asString()))
            .addAttendee(EventFields.Person.of("John2 Doe2", attendee1.username().asString()))
            .addAttendee(EventFields.Person.of(null, openPaasUser.username().asString()))
            .build();

        EventFields recurrenceEvent = EventFields.builder()
            .calendarURL(new CalendarURL(new OpenPaaSId(openPaasUser.id().value()), new OpenPaaSId(openPaasUser.id().value())))
            .uid(new EventUid(eventUid))
            .isRecurrentMaster(false)
            .summary(summary)
            .location(location)
            .description(description)
            .clazz("PUBLIC")
            .start(Instant.parse("2025-05-23T07:30:00Z"))
            .end(Instant.parse("2025-05-23T08:00:00Z"))
            .dtStamp(Instant.parse("2025-05-16T06:03:20Z"))
            .allDay(false)
            .organizer(EventFields.Person.of("John1 Doe1", openPaasUser.username().asString()))
            .addAttendee(EventFields.Person.of("John2 Doe2", attendee1.username().asString()))
            .addAttendee(EventFields.Person.of("John1 Doe1", openPaasUser.username().asString()))
            .build();

        assertThat(eventFields)
            .containsExactlyInAnyOrder(masterEvent, recurrenceEvent);

        // Check that the recurrence event is indexed for the attendee
        assertEventExistsInSearch(attendee1.username(), summary, eventUid);

        List<EventFields> eventFieldsAttendee = calendarSearchService.search(AccountId.fromUsername(openPaasUser.username()), simpleQuery(summary))
            .collectList()
            .block();

        assertThat(eventFieldsAttendee)
            .containsExactlyInAnyOrder(masterEvent, recurrenceEvent);
    }

    @Test
    void shouldUpdateRecurrenceEvent() {
        String eventUid = UUID.randomUUID().toString();
        String summary = "Meeting Summary";
        String description = "Detailed meeting description";
        String location = "Meeting Room 101";
        String organizer = openPaasUser.username().asString();
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();

        // 3 events: 1 master event and 2 recurrence events
        String calendarData = """
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
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250515T113000
            DTEND;TZID=Asia/Jakarta:20250515T120000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            RRULE:FREQ=WEEKLY;COUNT=14;BYDAY=FR
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.1:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:{organizer}
            DTSTAMP:20250516T060320Z
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250523T143000
            DTEND;TZID=Asia/Jakarta:20250523T150000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            DTSTAMP:20250516T060320Z
            RECURRENCE-ID:20250523T043000Z
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=John1
              Doe1:mailto:{organizer}
            SEQUENCE:1
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250530T143000
            DTEND;TZID=Asia/Jakarta:20250530T150000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            DTSTAMP:20250516T060320Z
            RECURRENCE-ID:20250530T043000Z
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=John1
              Doe1:mailto:{organizer}
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{summary}", summary)
            .replace("{description}", description)
            .replace("{location}", location)
            .replace("{organizer}", organizer)
            .replace("{attendee1}", attendee1.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        Supplier<Set<EventFields>> searchSupplier = () -> Flux.from(calendarSearchService.search(AccountId.fromUsername(openPaasUser.username()), simpleQuery(summary)))
            .collect(Collectors.toSet())
            .block();

        awaitAtMost.untilAsserted(() -> assertThat(searchSupplier.get()).hasSize(3));

        // Update the event (removed one recurrence event)
        String updatedCalendar = """
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
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250515T113000
            DTEND;TZID=Asia/Jakarta:20250515T120000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            RRULE:FREQ=WEEKLY;COUNT=14;BYDAY=FR
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.1:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:{organizer}
            DTSTAMP:20250516T060320Z
            END:VEVENT
            BEGIN:VEVENT
            UID:{eventUid}
            TRANSP:OPAQUE
            DTSTART;TZID=Asia/Jakarta:20250523T143000
            DTEND;TZID=Asia/Jakarta:20250523T150000
            CLASS:PUBLIC
            SUMMARY:{summary}
            DESCRIPTION:{description}
            LOCATION:{location}
            ORGANIZER;CN=John1 Doe1:mailto:{organizer}
            DTSTAMP:20250516T060320Z
            RECURRENCE-ID:20250523T043000Z
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVI
             DUAL;CN=John2 Doe2:mailto:{attendee1}
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL;CN=John1
              Doe1:mailto:{organizer}
            SEQUENCE:1
            END:VEVENT
            END:VCALENDAR
            """.replace("{eventUid}", eventUid)
            .replace("{summary}", summary)
            .replace("{description}", description)
            .replace("{location}", location)
            .replace("{organizer}", organizer)
            .replace("{attendee1}", attendee1.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, updatedCalendar, eventUid);

        awaitAtMost.untilAsserted(() -> assertThat(searchSupplier.get()).hasSize(2));
    }

    @Test
    void shouldRemoveEventFromSearchIndexForOrganizerWhenCalendarDeleted() {
        String eventUid = UUID.randomUUID().toString();
        String calendarData = getSampleCalendar(eventUid);
        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);

        davTestHelper.deleteCalendar(openPaasUser, eventUid);

        assertEventNotInSearch(openPaasUser.username(), "Test1", eventUid);
    }

    @Test
    void shouldUpdateEventInSearchIndexForOrganizerWhenCalendarEventIsUpdated() {
        String eventUid = UUID.randomUUID().toString();

        String sampleCalendar = getSampleCalendar(eventUid);
        String originalSummary = "Original Title";
        String updatedSummary = "Updated Title";
        String originalCalendar = StringUtils.replace(sampleCalendar, "Test1", originalSummary);
        String updatedCalendar = StringUtils.replace(sampleCalendar, "Test1", updatedSummary);

        davTestHelper.upsertCalendar(openPaasUser, originalCalendar, eventUid);

        assertEventExistsInSearch(openPaasUser.username(), originalSummary, eventUid);

        davTestHelper.updateCalendar(openPaasUser, updatedCalendar, eventUid);

        assertEventNotInSearch(openPaasUser.username(), originalSummary, eventUid);
        assertEventExistsInSearch(openPaasUser.username(), updatedSummary, eventUid);
    }

    @Test
    void shouldIndexEventInSearchEngineForAttendeeWhenInvited() {
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = getSampleCalendar(eventUid)
            .replace("organizer@open-paas.org", openPaasUser.username().asString())
            .replace("attendee1@open-paas.org", attendee1.username().asString())
            .replace("attendee2@open-paas.org", attendee2.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

        assertEventExistsInSearch(attendee1.username(), "Test1", eventUid);
        assertEventExistsInSearch(attendee2.username(), "Test1", eventUid);
    }

    @Test
    void shouldRemoveEventFromSearchIndexForAttendeeWhenCalendarDeleted() {
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String calendarData = getSampleCalendar(eventUid)
            .replace("organizer@open-paas.org", openPaasUser.username().asString())
            .replace("attendee1@open-paas.org", attendee1.username().asString())
            .replace("attendee2@open-paas.org", attendee2.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);
        assertEventExistsInSearch(attendee1.username(), "Test1", eventUid);
        assertEventExistsInSearch(attendee2.username(), "Test1", eventUid);

        davTestHelper.deleteCalendar(openPaasUser, eventUid);
        assertEventNotInSearch(attendee1.username(), "Test1", eventUid);
        assertEventNotInSearch(attendee2.username(), "Test1", eventUid);
    }

    @Test
    void shouldUpdateEventInSearchIndexForAttendeeWhenCalendarEventIsUpdated() {
        OpenPaaSUser attendee1 = sabreDavExtension.newTestUser();
        OpenPaaSUser attendee2 = sabreDavExtension.newTestUser();

        String eventUid = UUID.randomUUID().toString();
        String originalSummary = "Original Title";
        String updatedSummary = "Updated Summary";

        String originalCalendar = getSampleCalendar(eventUid)
            .replace("Test1", originalSummary)
            .replace("organizer@open-paas.org", openPaasUser.username().asString())
            .replace("attendee1@open-paas.org", attendee1.username().asString())
            .replace("attendee2@open-paas.org", attendee2.username().asString());

        davTestHelper.upsertCalendar(openPaasUser, originalCalendar, eventUid);
        assertEventExistsInSearch(attendee1.username(), originalSummary, eventUid);
        assertEventExistsInSearch(attendee2.username(), originalSummary, eventUid);

        String updatedCalendar = originalCalendar.replace(originalSummary, updatedSummary);

        davTestHelper.updateCalendar(openPaasUser, updatedCalendar, eventUid);

        assertEventNotInSearch(attendee1.username(), originalSummary, eventUid);
        assertEventNotInSearch(attendee2.username(), originalSummary, eventUid);
        assertEventExistsInSearch(attendee1.username(), updatedSummary, eventUid);
        assertEventExistsInSearch(attendee2.username(), updatedSummary, eventUid);
    }

    @Nested
    class FailureTest {

        @ParameterizedTest
        @ValueSource(strings = {"calendar:event:created", "calendar:event:updated", "calendar:event:request"})
        void consumeShouldNotCrashOnMalformedMessageOnCreatedAndUpdatedQueues(String exchangeName) {
            channelPool.getSender()
                .send(Mono.just(new OutboundMessage(exchangeName,
                    EMPTY_ROUTING_KEY,
                    "BAD_PAYLOAD".getBytes(UTF_8))))
                .block();

            String eventUid = UUID.randomUUID().toString();
            String calendarData = getSampleCalendar(eventUid);
            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

            assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);
        }

        @ParameterizedTest
        @ValueSource(strings = {"calendar:event:deleted", "calendar:event:cancel"})
        void consumeOnMalformedMessageOnDeletedAndCancelQueues(String exchangeName) {
            String eventUid = UUID.randomUUID().toString();
            String calendarData = getSampleCalendar(eventUid);
            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

            assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);

            channelPool.getSender()
                .send(Mono.just(new OutboundMessage(exchangeName,
                    EMPTY_ROUTING_KEY,
                    "BAD_PAYLOAD".getBytes(UTF_8))))
                .block();
            davTestHelper.deleteCalendar(openPaasUser, eventUid);

            assertEventNotInSearch(openPaasUser.username(), "Test1", eventUid);
        }

        @Test
        void shouldRecoverWhenIndexerFailsTemporarilyDuringIndexing() throws InterruptedException {
            Mockito.doReturn(Mono.defer(() -> Mono.error(new RuntimeException("mock exception"))))
                .when(calendarSearchService).index(any(), any());

            String eventUid = UUID.randomUUID().toString();
            String calendarData = getSampleCalendar(eventUid);
            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

            Thread.sleep(500);
            Mockito.reset(calendarSearchService);

            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

            assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);
        }

        @Test
        void shouldDeleteSuccessfullyAfterTemporaryIndexerFailure() throws InterruptedException {
            String eventUid = UUID.randomUUID().toString();
            String calendarData = getSampleCalendar(eventUid);
            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);
            assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);

            String eventUid2 = UUID.randomUUID().toString();
            String calendarData2 = getSampleCalendar(eventUid2).replace("Test1", "Test2");
            davTestHelper.upsertCalendar(openPaasUser, calendarData2, eventUid2);

            assertEventExistsInSearch(openPaasUser.username(), "Test2", eventUid2);

            Mockito.doReturn(Mono.defer(() -> Mono.error(new RuntimeException("mock delete exception"))))
                .when(calendarSearchService).delete(any(), any());

            davTestHelper.deleteCalendar(openPaasUser, eventUid);
            Thread.sleep(500);

            Mockito.reset(calendarSearchService);
            davTestHelper.deleteCalendar(openPaasUser, eventUid2);

            assertEventNotInSearch(openPaasUser.username(), "Test2", eventUid2);
        }

        @Test
        void consumeMessageShouldNotCrashWhenNotExistPrincipalUserId() throws Exception {
            String userId = ObjectId.getSmallestWithDate(new Date()).toString();
            String amqpMessage = """
                {"eventPath":"/calendars/%s/%s/a0b5a363-e56f-490b-bfa7-89111b0fdd9b.ics","event":["vcalendar",[["version",{},"text","2.0"],["prodid",{},"text","-//Sabre//Sabre VObject 4.1.3//EN"]],[["vtimezone",[["tzid",{},"text","Asia/Jakarta"]],[["standard",[["tzoffsetfrom",{},"utc-offset","+07:00"],["tzoffsetto",{},"utc-offset","+07:00"],["tzname",{},"text","WIB"],["dtstart",{},"date-time","1970-01-01T00:00:00"]],[]]]],["vevent",[["uid",{},"text","a0b5a363-e56f-490b-bfa7-89111b0fdd9b"],["transp",{},"text","OPAQUE"],["dtstart",{"tzid":"Asia/Saigon"},"date-time","2025-04-19T11:00:00"],["dtend",{"tzid":"Asia/Saigon"},"date-time","2025-04-19T11:30:00"],["class",{},"text","PUBLIC"],["summary",{},"text","Title 1"],["description",{},"text","note tung"],["organizer",{"cn":"John1 Doe1"},"cal-address","mailto:user1@open-paas.org"],["attendee",{"partstat":"NEEDS-ACTION","rsvp":"TRUE","role":"REQ-PARTICIPANT","cutype":"INDIVIDUAL","cn":"John2 Doe2","schedule-status":"1.1"},"cal-address","mailto:user2@open-paas.org"],["attendee",{"partstat":"ACCEPTED","rsvp":"FALSE","role":"CHAIR","cutype":"INDIVIDUAL"},"cal-address","mailto:user1@open-paas.org"],["dtstamp",{},"date-time","2025-04-18T07:47:48Z"]],[]]]],"import":false,"etag":"\\"f066260d3a4fca51ae0de0618e9555cc\\""}""".formatted(userId, userId);

            channelPool.getSender()
                .send(Mono.just(new OutboundMessage("calendar:event:created",
                    EMPTY_ROUTING_KEY,
                    amqpMessage.getBytes(UTF_8))))
                .block();

            Thread.sleep(200);
            String eventUid = UUID.randomUUID().toString();
            String calendarData = getSampleCalendar(eventUid);
            davTestHelper.upsertCalendar(openPaasUser, calendarData, eventUid);

            assertEventExistsInSearch(openPaasUser.username(), "Test1", eventUid);
        }
    }

    private void assertEventExistsInSearch(Username username, String query, String expectedUid) {
        awaitAtMost.untilAsserted(() -> assertThat(
            Flux.from(calendarSearchService.search(AccountId.fromUsername(username), simpleQuery(query)))
                .map(e -> e.uid().value())
                .collect(Collectors.toSet())
                .block()).containsExactly(expectedUid));
    }


    private void assertEventNotInSearch(Username username, String query, String unexpectedUid) {
        awaitAtMost.untilAsserted(() -> assertThat(
            Flux.from(calendarSearchService.search(AccountId.fromUsername(username), simpleQuery(query)))
                .map(e -> e.uid().value())
                .collectList()
                .block()).doesNotContain(unexpectedUid));
    }

    private EventSearchQuery simpleQuery(String query) {
        return new EventSearchQuery(query, Optional.empty(),
            Optional.empty(), Optional.empty(),
            EventSearchQuery.MAX_LIMIT, 0);
    }

    private String getSampleCalendar(String eventUid) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
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
            DTSTART;TZID=Asia/Jakarta:20250314T210000
            DTEND;TZID=Asia/Jakarta:20250314T220000
            CLASS:PUBLIC
            SUMMARY:Test1
            DESCRIPTION:Note1
            LOCATION:Location2
            ORGANIZER;CN=John1 Doe1:mailto:organizer@open-paas.org
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=TRUE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=John2 Doe2;SCHEDULE-STATUS=1.2:mailto:attendee1@open-paas.org
            ATTENDEE;PARTSTAT=ACCEPTED;RSVP=FALSE;ROLE=CHAIR;CUTYPE=INDIVIDUAL:mailto:attendee2@open-paas.org
            END:VEVENT
            END:VCALENDAR
            """.formatted(eventUid);
    }

}
