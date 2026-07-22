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

package com.linagora.calendar.webadmin;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalendarPropertiesUpdate;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.TeamCalendarInsertRequest;
import com.linagora.calendar.storage.TeamCalendarNotFoundException;
import com.linagora.calendar.storage.TeamCalendarRepository;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class TeamCalendarService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TeamCalendarService.class);

    private final OpenPaaSDomainDAO domainDAO;
    private final TeamCalendarRepository teamCalendarRepository;
    private final CalDavClient calDavClient;

    @Inject
    public TeamCalendarService(OpenPaaSDomainDAO domainDAO,
                               TeamCalendarRepository teamCalendarRepository,
                               CalDavClient calDavClient) {
        this.domainDAO = domainDAO;
        this.teamCalendarRepository = teamCalendarRepository;
        this.calDavClient = calDavClient;
    }

    public Mono<TeamCalendar> create(Domain domainName, String name, String displayName) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.create(new TeamCalendarInsertRequest(domain, name, displayName)))
            .flatMap(teamCalendar -> createDavCalendar(teamCalendar).thenReturn(teamCalendar));
    }

    public Flux<TeamCalendar> list(Domain domainName) {
        return resolveDomain(domainName)
            .flatMapMany(domain -> teamCalendarRepository.listByDomain(domain.id()));
    }

    public Mono<TeamCalendar> retrieve(Domain domainName, TeamCalendarId id) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.retrieve(id)
                .filter(teamCalendar -> teamCalendar.domain().id().equals(domain.id()))
                .switchIfEmpty(Mono.error(() -> new TeamCalendarNotFoundException(id))));
    }

    public Mono<TeamCalendar> updateDisplayName(Domain domainName, TeamCalendarId id, String displayName) {
        return retrieve(domainName, id)
            .flatMap(teamCalendar -> updateDavCalendar(teamCalendar, displayName)
                .then(teamCalendarRepository.updateDisplayName(id, displayName)));
    }

    public Mono<Void> delete(Domain domainName, TeamCalendarId id) {
        return resolveDomain(domainName)
            .flatMap(domain -> teamCalendarRepository.retrieve(id)
                .filter(teamCalendar -> teamCalendar.domain().id().equals(domain.id()))
                .flatMap(_ -> calDavClient.deleteCalendarHome(domain.id(), id.asOpenPaaSId())
                    .then(teamCalendarRepository.delete(id)))
                .then());
    }

    private Mono<OpenPaaSDomain> resolveDomain(Domain domainName) {
        return domainDAO.retrieve(domainName)
            .switchIfEmpty(Mono.error(() -> new DomainNotFoundException(domainName)));
    }

    private Mono<Void> createDavCalendar(TeamCalendar teamCalendar) {
        CalendarURL calendarURL = CalendarURL.from(teamCalendar.id().asOpenPaaSId());
        CalendarPropertiesUpdate calendarProperties = CalendarPropertiesUpdate.withName(
            StringUtils.defaultIfBlank(teamCalendar.displayName(), teamCalendar.name()));

        return calDavClient.propfindCalendarCollection(teamCalendar.domainId(), calendarURL)
            .then(calDavClient.updateCalendarProperties(teamCalendar.domainId(), calendarURL, calendarProperties))
            .doOnError(error -> LOGGER.error("Failed to create default DAV calendar for team calendar '{}' in domain '{}' at '{}'",
                teamCalendar.id().value(), teamCalendar.domainId().value(), calendarURL.asUri(), error));
    }

    private Mono<Void> updateDavCalendar(TeamCalendar teamCalendar, String displayName) {
        CalendarURL calendarURL = CalendarURL.from(teamCalendar.id().asOpenPaaSId());
        CalendarPropertiesUpdate calendarProperties = CalendarPropertiesUpdate.withName(displayName);

        return calDavClient.propfindCalendarCollection(teamCalendar.domainId(), calendarURL)
            .then(calDavClient.updateCalendarProperties(teamCalendar.domainId(), calendarURL, calendarProperties))
            .doOnError(error -> LOGGER.error("Failed to update default DAV calendar display name for team calendar '{}' in domain '{}' at '{}'",
                teamCalendar.id().value(), teamCalendar.domainId().value(), calendarURL.asUri(), error));
    }
}
