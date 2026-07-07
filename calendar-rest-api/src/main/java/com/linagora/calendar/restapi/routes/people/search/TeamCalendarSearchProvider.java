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

package com.linagora.calendar.restapi.routes.people.search;

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.model.TeamCalendar;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class TeamCalendarSearchProvider implements PeopleSearchProvider {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamCalendarResponseDTO(@JsonIgnore TeamCalendar teamCalendar) implements PeopleSearchRoute.ResponseDTO {

        @Override
        public String getId() {
            return teamCalendar.id().value();
        }

        @Override
        public String getObjectType() {
            return PeopleSearchRoute.ObjectType.TEAM_CALENDAR.serialize();
        }

        @Override
        public List<JsonNode> getEmailAddresses() {
            String mailAddress = teamCalendar.name() + "@" + teamCalendar.domain().domain().asString();
            return buildEmailAddresses(mailAddress, "default");
        }

        @Override
        public List<JsonNode> getNames() {
            return buildNames(teamCalendar.displayName());
        }

        @Override
        public List<JsonNode> getPhotos() {
            return List.of();
        }
    }

    private final OpenPaaSDomainDAO domainDAO;
    private final TeamCalendarRepository teamCalendarRepository;

    @Inject
    public TeamCalendarSearchProvider(OpenPaaSDomainDAO domainDAO, TeamCalendarRepository teamCalendarRepository) {
        this.domainDAO = domainDAO;
        this.teamCalendarRepository = teamCalendarRepository;
    }

    @Override
    public Set<PeopleSearchRoute.ObjectType> supportedTypes() {
        return ImmutableSet.of(PeopleSearchRoute.ObjectType.TEAM_CALENDAR);
    }

    @Override
    public Flux<PeopleSearchRoute.ResponseDTO> search(MailboxSession session, String query, Set<PeopleSearchRoute.ObjectType> objectTypesFilter, int limit) {
        return Mono.justOrEmpty(session.getUser().getDomainPart())
            .flatMap(domainDAO::retrieve)
            .flatMapMany(openPaaSDomain -> teamCalendarRepository.search(openPaaSDomain.id(), query, limit))
            .map(TeamCalendarResponseDTO::new);
    }
}
