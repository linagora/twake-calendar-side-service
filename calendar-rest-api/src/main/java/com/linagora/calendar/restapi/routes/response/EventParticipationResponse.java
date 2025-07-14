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

package com.linagora.calendar.restapi.routes.response;

import java.net.URI;
import java.util.Locale;

import org.apache.james.core.MailAddress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.dav.dto.VCalendarDto;

public record EventParticipationResponse(VCalendarDto event,
                                         MailAddress attendeeEmail,
                                         Links links,
                                         Locale locale) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Links(URI yes,
                        URI no,
                        URI maybe) {
    }

    public byte[] jsonAsBytes() throws JsonProcessingException {
        return MAPPER.writeValueAsBytes(ImmutableMap.builder()
            .put("eventJSON", event.value())
            .put("attendeeEmail", attendeeEmail.asString())
            .put("locale", locale.getLanguage())
            .put("links", ImmutableMap.of(
                "yes", links.yes.toString(),
                "no", links.no.toString(),
                "maybe", links.maybe.toString()))
            .build());
    }
}