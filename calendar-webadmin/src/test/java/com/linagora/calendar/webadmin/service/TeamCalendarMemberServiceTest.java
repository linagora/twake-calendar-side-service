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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the    *
 *  GNU Affero General Public License for more details.             *
 ********************************************************************/

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.AddSharee;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.RemoveSharee;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.Share;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.dav.SabreDavProvisioningService;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.mongodb.MongoDBTeamCalendarRepository;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService.TeamCalendarMember;
import com.linagora.calendar.webadmin.service.TeamCalendarMemberService.TeamCalendarRole;

class TeamCalendarMemberServiceTest {
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private OpenPaaSDomain domain;
    private MongoDBTeamCalendarRepository teamCalendarRepository;
    private CalDavClient calDavClient;
    private TeamCalendarMemberService testee;

    @BeforeEach
    void setUp() throws SSLException {
        domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of(SabreDavProvisioningService.DOMAIN))
            .block();
        teamCalendarRepository = new MongoDBTeamCalendarRepository(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), Clock.systemUTC());
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        testee = new TeamCalendarMemberService(calDavClient);
    }

    @Test
    void listShouldMapDavInvitesToTeamCalendarMembers() {
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser viewer = sabreDavExtension.newTestUser(Optional.of("viewer_"));
        OpenPaaSUser member = sabreDavExtension.newTestUser(Optional.of("member_"));
        OpenPaaSUser manager = sabreDavExtension.newTestUser(Optional.of("manager_"));

        testee.update(domain.id(), teamCalendar.id(), sharingUpdate(viewer, member, manager, List.of())).block();

        List<TeamCalendarMember> members = testee.list(domain.id(), teamCalendar.id()).collectList().block();

        assertThat(members).containsExactlyInAnyOrder(
            new TeamCalendarMember(viewer.username(), TeamCalendarRole.VIEWER),
            new TeamCalendarMember(member.username(), TeamCalendarRole.MEMBER),
            new TeamCalendarMember(manager.username(), TeamCalendarRole.MANAGER));
    }

    @Test
    void updateShouldSupportSetAndRemoveInTheSameRequest() {
        TeamCalendar teamCalendar = createTeamCalendar();
        OpenPaaSUser viewer = sabreDavExtension.newTestUser(Optional.of("viewer_"));
        OpenPaaSUser member = sabreDavExtension.newTestUser(Optional.of("member_"));
        OpenPaaSUser manager = sabreDavExtension.newTestUser(Optional.of("manager_"));
        OpenPaaSUser removed = sabreDavExtension.newTestUser(Optional.of("removed_"));

        testee.update(domain.id(), teamCalendar.id(), sharingUpdate(removed, member, manager, List.of())).block();

        CalendarSharingUpdate update = sharingUpdate(viewer, member, manager, List.of(removed));
        testee.update(domain.id(), teamCalendar.id(), update).block();

        List<String> memberHrefs = calDavClient.fetchCalendarDetails(domain.id(), CalendarURL.from(teamCalendar.id().asOpenPaaSId()), WITH_RIGHTS)
            .flatMapIterable(CalendarDetailsResponse::invites)
            .map(CalendarInvite::href)
            .filter(href -> href.startsWith("mailto:"))
            .collectList()
            .block();

        assertThat(memberHrefs).containsExactlyInAnyOrder(
            mailto(viewer),
            mailto(member),
            mailto(manager));
    }

    private TeamCalendar createTeamCalendar() {
        return teamCalendarRepository.create(new TeamCalendarInsertRequest(
                domain,
                "team-" + UUID.randomUUID(),
                "Team calendar"))
            .block();
    }

    private CalendarSharingUpdate sharingUpdate(OpenPaaSUser viewer,
                                                OpenPaaSUser member,
                                                OpenPaaSUser manager,
                                                List<OpenPaaSUser> removedUsers) {
        return new CalendarSharingUpdate(new Share(
            List.of(
                AddSharee.read(mailto(viewer)),
                AddSharee.readWrite(mailto(member)),
                AddSharee.administration(mailto(manager))),
            removedUsers.stream()
                .map(user -> new RemoveSharee(mailto(user)))
                .toList()));
    }

    private String mailto(OpenPaaSUser user) {
        return "mailto:" + user.username().asString();
    }
}
