/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.calendar.dav;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class CalendarSharingUpdateTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeToSabreWireFormat() throws Exception {
        CalendarSharingUpdate update = CalendarSharingUpdate.builder()
            .grant(Username.of("viewer@example.com"), DavRight.READ)
            .grant(Username.of("member@example.com"), DavRight.READ_WRITE)
            .grant(Username.of("manager@example.com"), DavRight.ADMINISTRATION)
            .revoke(Username.of("removed@example.com"))
            .build();

        assertThatJson(OBJECT_MAPPER.writeValueAsString(update)).isEqualTo("""
            {
              "share": {
                "set": [
                  {"dav:href": "mailto:viewer@example.com", "dav:read": true},
                  {"dav:href": "mailto:member@example.com", "dav:read-write": true},
                  {"dav:href": "mailto:manager@example.com", "dav:administration": true}
                ],
                "remove": [
                  {"dav:href": "mailto:removed@example.com"}
                ]
              }
            }
            """);
    }

    @Test
    void shouldDeserializeDavRightFromSabreWireFormat() throws Exception {
        CalendarSharingUpdate actual = OBJECT_MAPPER.readValue("""
            {
              "share": {
                "set": [{"dav:href": "mailto:member@example.com", "dav:read-write": true}],
                "remove": []
              }
            }
            """, CalendarSharingUpdate.class);

        assertThat(actual.share().set())
            .containsExactly(CalendarSharingUpdate.AddSharee.of(
                Username.of("member@example.com"), DavRight.READ_WRITE));
    }

    @Test
    void shouldRejectMoreThanOneEnabledDavRight() {
        assertThatThrownBy(() -> OBJECT_MAPPER.readValue("""
            {
              "share": {
                "set": [{
                  "dav:href": "mailto:member@example.com",
                  "dav:read": true,
                  "dav:read-write": true
                }],
                "remove": []
              }
            }
            """, CalendarSharingUpdate.class))
            .hasRootCauseInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Exactly one of 'dav:read', 'dav:read-write', 'dav:administration' must be true");
    }
}
