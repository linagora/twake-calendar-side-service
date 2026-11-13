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

import java.net.URI;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;
import com.linagora.calendar.storage.CalendarURL;

/**
 * Sabre materializes a <em>mirror</em> calendar within the calendar home of a user that subscribed to a
 * public calendar ({@code calendarserver:source}) or that got a calendar delegated to them
 * ({@code calendarserver:delegatedsource}). Such a mirror merely points back to the source calendar, which
 * remains owned - and thus solely administrable - by another user.
 *
 * <p>esn-sabre keeps listing a subscription whose source the subscriber may only read the free/busy of, but
 * renders that source reduced to its {@code _links}: {@code subscribedSourceReadable} is false then.</p>
 */
public record CalendarMirrorSource(Optional<CalendarURL> subscribedSource,
                                   boolean subscribedSourceReadable,
                                   Optional<CalendarURL> delegatedSource) {
    private static final String JSON_EXTENSION = ".json";
    private static final String LINKS = "_links";

    public static CalendarMirrorSource parse(JsonNode calendarMetadata) {
        JsonNode subscribedSourceNode = calendarMetadata.path("calendarserver:source");
        return new CalendarMirrorSource(
            toCalendarURL(subscribedSourceNode
                .path(LINKS)
                .path("self")
                .path("href")),
            isFullyRendered(subscribedSourceNode),
            toCalendarURL(calendarMetadata.path("calendarserver:delegatedsource")));
    }

    /**
     * esn-sabre renders a source the subscriber may read with its properties and ACL, and one they may only
     * read the free/busy of with nothing but its {@code _links}.
     */
    private static boolean isFullyRendered(JsonNode sourceNode) {
        return sourceNode.isObject()
            && Streams.stream(sourceNode.fieldNames()).anyMatch(field -> !LINKS.equals(field));
    }

    private static Optional<CalendarURL> toCalendarURL(JsonNode hrefNode) {
        return Optional.ofNullable(hrefNode.asText(null))
            .filter(StringUtils::isNotBlank)
            .map(URI::create)
            .map(href -> CalendarURL.parse(Strings.CS.removeEnd(href.getPath(), JSON_EXTENSION)));
    }

    public Optional<CalendarURL> sourceCalendarURL() {
        return subscribedSource.or(() -> delegatedSource);
    }

    /**
     * The source calendar whose events the requester may read through this mirror: a subscribed source rendered
     * for free/busy only does not qualify.
     */
    public Optional<CalendarURL> readableSourceCalendarURL() {
        if (subscribedSource.isPresent()) {
            return subscribedSource.filter(any -> subscribedSourceReadable);
        }
        return delegatedSource;
    }

    public boolean isMirror() {
        return sourceCalendarURL().isPresent();
    }
}
