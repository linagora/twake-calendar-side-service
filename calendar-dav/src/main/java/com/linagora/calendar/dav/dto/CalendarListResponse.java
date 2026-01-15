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


import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.collect.Streams;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.storage.CalendarURL;

public record CalendarListResponse(Map<CalendarURL, JsonNode> calendars) {

    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static CalendarListResponse parse(byte[] payload) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(payload);
            ArrayNode calendarsNode = (ArrayNode) root.path("_embedded").path("dav:calendar");

            Map<CalendarURL, JsonNode> calendars = Streams.stream(calendarsNode.elements())
                .map(calendarNode -> {
                    String href = calendarNode.path("_links").path("self").path("href").asText();

                    if (StringUtils.isBlank(href)) {
                        return null;
                    }

                    CalendarURL calendarURL = CalendarURL.parse(Strings.CS.replace(href, ".json", ""));
                    return Map.entry(calendarURL, calendarNode);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

            return new CalendarListResponse(calendars);
        } catch (Exception e) {
            throw new DavClientException("Failed to parse calendar list response", e);
        }
    }
}