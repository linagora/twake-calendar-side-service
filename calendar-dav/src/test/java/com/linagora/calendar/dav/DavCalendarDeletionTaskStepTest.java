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

package com.linagora.calendar.dav;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.dav.CalDavClient.PublicRight;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import net.fortuna.ical4j.model.Component;

public class DavCalendarDeletionTaskStepTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private static DavTestHelper davTestHelper;

    private DavCalendarDeletionTaskStep testee;
    private CalDavClient calDavClient;

    private OpenPaaSUser openPaaSUser;
    private OpenPaaSUser openPaaSUser2;

    @BeforeAll
    static void setUpAll() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @BeforeEach
    void setUp() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        testee = new DavCalendarDeletionTaskStep(calDavClient, new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO));

        this.openPaaSUser = sabreDavExtension.newTestUser();
        this.openPaaSUser2 = sabreDavExtension.newTestUser();
    }

    @Test
    void deleteUserDataShouldDeleteNonPrimaryCalendars() {
        String nonPrimaryCalendarId = "test-calendar";
        CalendarURL calendarURL = new CalendarURL(openPaaSUser.id(), new OpenPaaSId(nonPrimaryCalendarId));
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            nonPrimaryCalendarId,
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );

        calDavClient.createNewCalendar(openPaaSUser.username(), openPaaSUser.id(), newCalendar).block();

        testee.deleteUserData(openPaaSUser.username()).block();

        var actual = calDavClient.findUserCalendars(openPaaSUser.username(), openPaaSUser.id())
            .collectList().block();

        assertThat(actual).doesNotContain(calendarURL);
    }

    @Test
    void deleteUserDataShouldDeletePrimaryCalendarEvents() {
        CalendarURL primaryCalendarURL = CalendarURL.from(openPaaSUser.id());

        String uid1 = "event-1";
        String uid2 = "event-2";
        String ics1 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Event 1
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1);
            String ics2 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250103T120000Z
            DTEND:20250103T130000Z
            SUMMARY:Event 2
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid2);

        // Ensure calendar directory is activated
        calDavClient.export(primaryCalendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(primaryCalendarURL, uid1, openPaaSUser.username(), ics1.getBytes()).block();
        calDavClient.importCalendar(primaryCalendarURL, uid2, openPaaSUser.username(), ics2.getBytes()).block();

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(CalendarUtil.parseIcs(calDavClient.export(primaryCalendarURL, openPaaSUser.username()).block())
            .getComponents(Component.VEVENT))
            .isEmpty();
    }

    @Test
    void deleteUserDataShouldNotDeleteNonPrimaryCalendarsOfOtherUser() {
        String nonPrimaryCalendarId = "test-calendar";
        CalendarURL nonPrimaryCalendarUrl = new CalendarURL(openPaaSUser.id(), new OpenPaaSId(nonPrimaryCalendarId));
        CalDavClient.NewCalendar newCalendar = new CalDavClient.NewCalendar(
            nonPrimaryCalendarId,
            "Test Calendar",
            "#97c3c1",
            "A test calendar"
        );

        calDavClient.createNewCalendar(openPaaSUser.username(), openPaaSUser.id(), newCalendar).block();

        testee.deleteUserData(openPaaSUser2.username()).block();

        var actual = calDavClient.findUserCalendars(openPaaSUser.username(), openPaaSUser.id())
            .collectList().block();

        assertThat(actual).contains(nonPrimaryCalendarUrl);
    }

    @Test
    void deleteUserDataShouldNotDeletePrimaryCalendarEventsOfOtherUser() {
        CalendarURL primaryCalendarURL = CalendarURL.from(openPaaSUser.id());
        String uid1 = "event-1";
        String ics1 = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20250101T100000Z
            DTSTART:20250102T120000Z
            DTEND:20250102T130000Z
            SUMMARY:Event 1
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid1);

        // Ensure calendar directory is activated
        calDavClient.export(primaryCalendarURL, openPaaSUser.username()).block();

        calDavClient.importCalendar(primaryCalendarURL, uid1, openPaaSUser.username(), ics1.getBytes()).block();

        testee.deleteUserData(openPaaSUser2.username()).block();

        assertThat(CalendarUtil.parseIcs(calDavClient.export(primaryCalendarURL, openPaaSUser.username()).block())
            .getComponents(Component.VEVENT))
            .isNotEmpty();
    }

    @Test
    void deleteUserDataShouldRevokeDelegationOfPrimaryCalendar() {
        davTestHelper.grantDelegation(openPaaSUser, CalendarURL.from(openPaaSUser.id()), openPaaSUser2, "dav:read-write");
        assertThat(mirrorCalendars(openPaaSUser2)).isNotEmpty();

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(mirrorCalendars(openPaaSUser2)).isEmpty();
    }

    @Test
    void deleteUserDataShouldRevokeDelegationOfNonPrimaryCalendar() {
        CalendarURL calendarURL = createCalendar(openPaaSUser, PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(openPaaSUser, calendarURL, openPaaSUser2, "dav:read");
        assertThat(mirrorCalendars(openPaaSUser2)).isNotEmpty();

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(mirrorCalendars(openPaaSUser2)).isEmpty();
    }

    @Test
    void deleteUserDataShouldRemovePublicSubscriptionsToUserCalendars() {
        CalendarURL calendarURL = createCalendar(openPaaSUser, PublicRight.READ);
        subscribeTo(openPaaSUser2, calendarURL);
        assertThat(mirrorCalendars(openPaaSUser2)).isNotEmpty();

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(mirrorCalendars(openPaaSUser2)).isEmpty();
    }

    @Test
    void deleteUserDataShouldDeletePublicSubscriptionsOfTheUserWithoutAffectingTheSourceCalendar() {
        CalendarURL sourceCalendarURL = createCalendar(openPaaSUser2, PublicRight.READ);
        subscribeTo(openPaaSUser, sourceCalendarURL);

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(mirrorCalendars(openPaaSUser)).isEmpty();
        assertThat(calDavClient.findUserCalendars(openPaaSUser2.username(), openPaaSUser2.id()).collectList().block())
            .contains(sourceCalendarURL);
    }

    @Test
    void deleteUserDataShouldNotRevokeDelegationGrantedByOtherUsers() {
        CalendarURL calendarURL = createCalendar(openPaaSUser2, PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(openPaaSUser2, calendarURL, openPaaSUser, "dav:read");

        testee.deleteUserData(openPaaSUser.username()).block();

        assertThat(calDavClient.fetchCalendarDetails(openPaaSUser2.username(), calendarURL, Map.of("withRights", "true"))
            .block()
            .invites())
            .anySatisfy(invite -> assertThat(invite.href()).isEqualTo("mailto:" + openPaaSUser.username().asString()));
    }

    private CalendarURL createCalendar(OpenPaaSUser owner, PublicRight publicRight) {
        String calendarId = "calendar-" + UUID.randomUUID();
        calDavClient.createNewCalendar(owner.username(), owner.id(),
            new CalDavClient.NewCalendar(calendarId, "Test Calendar", "#97c3c1", "A test calendar")).block();

        CalendarURL calendarURL = new CalendarURL(owner.id(), new OpenPaaSId(calendarId));
        calDavClient.updateCalendarAcl(owner.username(), calendarURL, publicRight).block();
        return calendarURL;
    }

    private void subscribeTo(OpenPaaSUser subscriber, CalendarURL sourceCalendarURL) {
        davTestHelper.subscribeToSharedCalendar(subscriber, SubscribedCalendarRequest.builder()
            .id("subscription-" + UUID.randomUUID())
            .sourceUserId(sourceCalendarURL.base().value())
            .sourceCalendarId(sourceCalendarURL.calendarId().value())
            .name("My subscription")
            .color("#00FF00")
            .readOnly(true)
            .build());
    }

    private List<CalendarURL> mirrorCalendars(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id())
            .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(user.id())))
            .collectList()
            .block();
    }
}
