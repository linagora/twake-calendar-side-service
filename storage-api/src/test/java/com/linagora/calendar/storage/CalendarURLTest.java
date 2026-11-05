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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CalendarURLTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "base1/calendar1",
        "/base1/calendar1",
        "/calendars/base1/calendar1",
        "/calendars/base1/calendar1/",
        "/calendars/base1/calendar1/3423434.ics"
    })
    void parseShouldAcceptVariousFormats(String input) {
        assertThat(CalendarURL.parse(input))
            .isEqualTo(new CalendarURL(new OpenPaaSId("base1"), new OpenPaaSId("calendar1")));
    }

    @Test
    void parseShouldRejectInvalidFormat() {
        assertThatThrownBy(() -> CalendarURL.parse("baseOnly"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseShouldRejectEmptyInput() {
        assertThatThrownBy(() -> CalendarURL.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/calendars/ac/..", "/calendars/c/.", "/calendars/a?b/c", "/calendars/a#b/c", "/calendars/%/c", "/calendars/a b/c", "/calendars/e/#", "/calendars/a\\b/c"})
    void parseShouldRejectIdsThatAreNotSinglePathSegments(String rawValue) {
        assertThatThrownBy(() -> CalendarURL.parse(rawValue))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%2F", "%2f", "a%2Fb", "%5C", "%2e%2e", "%2E.", "%2e", "%00", "%0A", "%7F", "%C3%28"})
    void parseShouldRejectIdsDecodingToSeveralSegments(String id) {
        assertThatThrownBy(() -> CalendarURL.parse("/calendars/base1/" + id))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CalendarURL(new OpenPaaSId(id), new OpenPaaSId("c")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/calendars/base1/a.b", "/calendars/base1/a%20b", "/calendars/base1/a%3Fb", "/calendars/base1/a%23b", "/calendars/base1/%3F", "/calendars/base1/%23", "/calendars/base1/user@domain.tld", "/calendars/base1/a-b_c~d+e"})
    void parseShouldAcceptIdsThatAreSinglePathSegments(String rawValue) {
        assertThat(CalendarURL.parse(rawValue).asUri().getRawPath())
            .isEqualTo(rawValue);
    }

    @ParameterizedTest
    @ValueSource(strings = {"calendars/b", "calendars/calendars", "b/calendars"})
    void deserializeShouldReadBackSerialize(String serialized) {
        String[] ids = serialized.split("/");
        CalendarURL url = new CalendarURL(new OpenPaaSId(ids[0]), new OpenPaaSId(ids[1]));

        assertThat(CalendarURL.deserialize(url.serialize())).isEqualTo(url);
    }

    @Test
    void parseShouldRejectCalendarsSegmentAlone() {
        assertThatThrownBy(() -> CalendarURL.parse("/calendars"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorShouldRejectDotSegments() {
        assertThatThrownBy(() -> new CalendarURL(new OpenPaaSId("base1"), new OpenPaaSId("..")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
