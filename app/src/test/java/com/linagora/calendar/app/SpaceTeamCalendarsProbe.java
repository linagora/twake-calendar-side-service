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

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.twakespace.SpaceTeamCalendars;
import com.linagora.calendar.twakespace.model.SpaceId;

import reactor.core.publisher.Mono;

public class SpaceTeamCalendarsProbe implements GuiceProbe {
    private final SpaceTeamCalendars spaceTeamCalendars;

    @Inject
    public SpaceTeamCalendarsProbe(SpaceTeamCalendars spaceTeamCalendars) {
        this.spaceTeamCalendars = spaceTeamCalendars;
    }

    public Mono<TeamCalendar> findOrCreate(Domain domain, SpaceId spaceId, Optional<String> name) {
        return spaceTeamCalendars.findOrCreate(domain, spaceId, name);
    }

    public Mono<TeamCalendar> find(Domain domain, SpaceId spaceId) {
        return spaceTeamCalendars.find(domain, spaceId);
    }
}
