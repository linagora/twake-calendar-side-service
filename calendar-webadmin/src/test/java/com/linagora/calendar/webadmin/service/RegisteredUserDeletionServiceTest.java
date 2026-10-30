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

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarSharingUpdate.AddSharee;
import com.linagora.calendar.dav.CalendarSharingUpdate;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;
import com.linagora.calendar.dav.dto.CalendarMirrorSource;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;

class RegisteredUserDeletionServiceTest {
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private static DavTestHelper davTestHelper;

    private OpenPaaSDomain domain;
    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBTeamCalendarRepository teamCalendarRepository;
    private MongoDBResourceDAO resourceDAO;
    private CalDavClient calDavClient;
    private RegisteredUserDeletionService testee;

    @BeforeAll
    static void setUpAll() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @BeforeEach
    void setUp() throws SSLException {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of(SabreDavProvisioningService.DOMAIN))
            .block();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongoDB);
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, domainDAO);
        teamCalendarRepository = new MongoDBTeamCalendarRepository(mongoDB, Clock.systemUTC());
        resourceDAO = new MongoDBResourceDAO(mongoDB, Clock.systemUTC());
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        testee = new RegisteredUserDeletionService(userDAO, domainDAO, calDavClient);
    }

    @Test
    void deleteShouldRemoveTheUser() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();

        testee.delete(user).block();

        assertThat(userDAO.retrieve(user.username()).blockOptional()).isEmpty();
    }

    @Test
    void deleteShouldRemoveTheUserFromTeamCalendarMembers() {
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        OpenPaaSUser kept = sabreDavExtension.newTestUser(Optional.of("kept_"));
        share(teamCalendar, CalendarSharingUpdate.builder()
            .grant(deleted.username(), DavRight.READ)
            .grant(kept.username(), DavRight.READ_WRITE)
            .build());

        testee.delete(deleted).block();

        assertThat(teamCalendarSharees(teamCalendar)).containsExactly(mailto(kept));
    }

    @Test
    void deleteShouldKeepTeamCalendarWritable() {
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        share(teamCalendar, CalendarSharingUpdate.grant(deleted.username(), DavRight.READ));

        testee.delete(deleted).block();

        String eventId = UUID.randomUUID().toString();
        assertThatCode(() -> calDavClient.importCalendar(domain.id(), teamCalendarURL(teamCalendar), eventId, event(eventId)).block())
            .doesNotThrowAnyException();
    }

    @Test
    void deleteShouldHandBackDelegationsOfUserCalendars() {
        OpenPaaSUser owner = sabreDavExtension.newTestUser(Optional.of("owner_"));
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        CalendarURL ownerCalendar = CalendarURL.from(owner.id());
        davTestHelper.grantDelegation(owner, ownerCalendar, deleted, "dav:read-write");

        testee.delete(deleted).block();

        assertThat(calDavClient.fetchCalendarDetails(owner.username(), ownerCalendar, WITH_RIGHTS).block().invites())
            .extracting(CalendarInvite::href)
            .doesNotContain(mailto(deleted));
    }

    @Test
    void deleteShouldRevokeDelegationsGrantedByTheUser() {
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        OpenPaaSUser delegate = sabreDavExtension.newTestUser(Optional.of("delegate_"));
        CalendarURL deletedCalendar = CalendarURL.from(deleted.id());
        davTestHelper.grantDelegation(deleted, deletedCalendar, delegate, "dav:read-write");

        testee.delete(deleted).block();

        assertThat(delegatedSources(delegate)).doesNotContain(deletedCalendar);
    }

    @Test
    void deleteShouldKeepDelegationsGrantedByOtherUsers() {
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        OpenPaaSUser owner = sabreDavExtension.newTestUser(Optional.of("owner_"));
        OpenPaaSUser delegate = sabreDavExtension.newTestUser(Optional.of("delegate_"));
        CalendarURL ownerCalendar = CalendarURL.from(owner.id());
        davTestHelper.grantDelegation(owner, ownerCalendar, delegate, "dav:read-write");

        testee.delete(deleted).block();

        assertThat(delegatedSources(delegate)).containsExactly(ownerCalendar);
    }

    @Test
    void deleteShouldRemoveTheUserFromResourceAdministrators() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        OpenPaaSUser kept = sabreDavExtension.newTestUser(Optional.of("kept_"));
        CalendarURL resourceCalendar = createResourceCalendar(creator);
        calDavClient.updateCalendarShares(domain.id(), resourceCalendar, CalendarSharingUpdate.grant(
                AddSharee.of(deleted.username(), DavRight.READ_WRITE),
                AddSharee.of(kept.username(), DavRight.READ_WRITE)))
            .block();

        testee.delete(deleted).block();

        assertThat(sharees(resourceCalendar)).containsExactly(mailto(kept));
    }

    @Test
    void deleteShouldKeepResourceCalendarWritable() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser deleted = sabreDavExtension.newTestUser(Optional.of("deleted_"));
        CalendarURL resourceCalendar = createResourceCalendar(creator);
        calDavClient.updateCalendarShares(domain.id(), resourceCalendar, CalendarSharingUpdate.grant(deleted.username(), DavRight.READ_WRITE))
            .block();

        testee.delete(deleted).block();

        String eventId = UUID.randomUUID().toString();
        assertThatCode(() -> calDavClient.importCalendar(domain.id(), resourceCalendar, eventId, event(eventId)).block())
            .doesNotThrowAnyException();
    }

    private TeamCalendar createTeamCalendar() {
        return teamCalendarRepository.create(new TeamCalendarInsertRequest(
                domain,
                "team-" + UUID.randomUUID(),
                "Team calendar"))
            .block();
    }

    private void share(TeamCalendar teamCalendar, CalendarSharingUpdate sharingUpdate) {
        calDavClient.updateCalendarShares(domain.id(), teamCalendarURL(teamCalendar), sharingUpdate).block();
    }

    private CalendarURL createResourceCalendar(OpenPaaSUser creator) {
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(
                creator.id(),
                "Resource",
                domain.id(),
                "projector",
                "Resource " + UUID.randomUUID()))
            .block();
        return CalendarURL.from(resourceId.asOpenPaaSId());
    }

    private List<CalendarURL> delegatedSources(OpenPaaSUser delegate) {
        return Flux.just("noresponse", "accepted")
            .concatMap(status -> calDavClient.findUserCalendars(delegate.username(), delegate.id(), Map.of("sharedDelegationStatus", status)))
            .flatMapIterable(calendarList -> calendarList.calendars().values())
            .flatMap(calendarMetadata -> Flux.fromStream(CalendarMirrorSource.parse(calendarMetadata).delegatedSource().stream()))
            .collectList()
            .block();
    }

    private List<String> teamCalendarSharees(TeamCalendar teamCalendar) {
        return sharees(teamCalendarURL(teamCalendar));
    }

    private List<String> sharees(CalendarURL calendarURL) {
        return calDavClient.fetchCalendarDetails(domain.id(), calendarURL, WITH_RIGHTS)
            .flatMapIterable(CalendarDetailsResponse::invites)
            .map(CalendarInvite::href)
            .filter(href -> href.startsWith("mailto:"))
            .collectList()
            .block();
    }

    private static CalendarURL teamCalendarURL(TeamCalendar teamCalendar) {
        return CalendarURL.from(teamCalendar.id().asOpenPaaSId());
    }

    private static byte[] event(String uid) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Test//EN
            BEGIN:VEVENT
            UID:%s
            DTSTAMP:20260101T000000Z
            DTSTART:20260110T100000Z
            DTEND:20260110T110000Z
            SUMMARY:Team meeting
            END:VEVENT
            END:VCALENDAR
            """.formatted(uid).replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String mailto(OpenPaaSUser user) {
        return "mailto:" + user.username().asString();
    }
}
