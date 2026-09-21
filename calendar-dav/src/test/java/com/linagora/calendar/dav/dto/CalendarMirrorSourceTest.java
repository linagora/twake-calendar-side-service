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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

class CalendarMirrorSourceTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final CalendarURL SOURCE_CALENDAR = new CalendarURL(new OpenPaaSId("ownerId"), new OpenPaaSId("calendarId"));

    private static JsonNode parseJson(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldResolveSubscribedSource() {
        CalendarMirrorSource mirrorSource = CalendarMirrorSource.parse(parseJson("""
            {
              "calendarserver:source": {
                "_links": {
                  "self": {
                    "href": "/calendars/ownerId/calendarId.json"
                  }
                }
              }
            }"""));

        assertThat(mirrorSource.isMirror()).isTrue();
        assertThat(mirrorSource.sourceCalendarURL()).contains(SOURCE_CALENDAR);
    }

    @Test
    void shouldResolveDelegatedSource() {
        CalendarMirrorSource mirrorSource = CalendarMirrorSource.parse(parseJson("""
            {
              "calendarserver:delegatedsource": "/calendars/ownerId/calendarId.json"
            }"""));

        assertThat(mirrorSource.isMirror()).isTrue();
        assertThat(mirrorSource.sourceCalendarURL()).contains(SOURCE_CALENDAR);
    }

    @Test
    void subscribedSourceShouldTakePrecedenceOverDelegatedSource() {
        CalendarMirrorSource mirrorSource = CalendarMirrorSource.parse(parseJson("""
            {
              "calendarserver:source": {
                "_links": {
                  "self": {
                    "href": "/calendars/ownerId/calendarId.json"
                  }
                }
              },
              "calendarserver:delegatedsource": "/calendars/otherOwnerId/otherCalendarId.json"
            }"""));

        assertThat(mirrorSource.sourceCalendarURL()).contains(SOURCE_CALENDAR);
    }

    @Test
    void ownedCalendarShouldNotBeAMirror() {
        CalendarMirrorSource mirrorSource = CalendarMirrorSource.parse(parseJson("""
            {
              "dav:name": "My calendar",
              "_links": {
                "self": {
                  "href": "/calendars/ownerId/calendarId.json"
                }
              }
            }"""));

        assertThat(mirrorSource.isMirror()).isFalse();
        assertThat(mirrorSource.sourceCalendarURL()).isEqualTo(Optional.empty());
    }

    @Test
    void blankSourceHrefShouldNotBeAMirror() {
        CalendarMirrorSource mirrorSource = CalendarMirrorSource.parse(parseJson("""
            {
              "calendarserver:source": {
                "_links": {
                  "self": {
                    "href": ""
                  }
                }
              },
              "calendarserver:delegatedsource": ""
            }"""));

        assertThat(mirrorSource.isMirror()).isFalse();
    }
}
