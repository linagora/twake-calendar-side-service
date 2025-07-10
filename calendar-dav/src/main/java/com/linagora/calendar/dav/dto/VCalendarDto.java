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

package com.linagora.calendar.dav.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record VCalendarDto(JsonNode value) {
    public static VCalendarDto from(CalendarEventReportResponse reportResponse) {
        JsonNode davItem = reportResponse.firstDavItemNode();
        JsonNode data = davItem.path("data");
        if (data.isMissingNode() || !data.isArray()) {
            throw new IllegalStateException("Missing or invalid 'data' field in 'dav:item'");
        }

        return new VCalendarDto(data);
    }

}
