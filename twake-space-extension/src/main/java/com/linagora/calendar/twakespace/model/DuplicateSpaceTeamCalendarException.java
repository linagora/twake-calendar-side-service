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

package com.linagora.calendar.twakespace.model;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.james.core.Domain;

import com.linagora.calendar.storage.model.TeamCalendarId;

public class DuplicateSpaceTeamCalendarException extends RuntimeException {
    public DuplicateSpaceTeamCalendarException(SpaceId spaceId, Domain domain, List<TeamCalendarId> teamCalendars) {
        super("Space " + spaceId + " has " + teamCalendars.size() + " team calendars in " + domain.asString() + ": "
            + teamCalendars.stream().map(TeamCalendarId::value).collect(Collectors.joining(", "))
            + ". Delete all but one with the team calendar webadmin routes.");
    }
}
