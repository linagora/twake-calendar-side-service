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

package com.linagora.calendar.dav.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;

import com.linagora.calendar.dav.model.CalendarQuery.AttendeePropFilter;
import com.linagora.calendar.dav.model.CalendarQuery.TimeRangePropFilter;

public class CalendarQueryTest {

    @Test
    void shouldGenerateCalendarQueryReportWithSingleDtStampBefore() throws Exception {
        Instant end = Instant.parse("2027-01-01T00:00:00Z");

        CalendarQuery query = CalendarQuery.ofFilters(TimeRangePropFilter.dtStampBefore(end));

        String xml = query.toCalendarQueryReport();

        String expected = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <C:calendar-query xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <D:prop>
                    <C:calendar-data/>
                </D:prop>
                <C:filter>
                    <C:comp-filter name="VCALENDAR">
                        <C:comp-filter name="VEVENT">
                            <C:prop-filter name="DTSTAMP">
                                <C:time-range end="20270101T000000Z"/>
                            </C:prop-filter>
                        </C:comp-filter>
                    </C:comp-filter>
                </C:filter>
            </C:calendar-query>
            """;

        assertXmlSimilar(expected, xml);
    }

    @Test
    void shouldGenerateCalendarQueryReportWithSingleAttendeeFilter() throws Exception {
        Username attendee = Username.of("lisa@example.com");

        CalendarQuery query = CalendarQuery.ofFilters(AttendeePropFilter.declined(attendee));

        String xml = query.toCalendarQueryReport();

        String expected = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <C:calendar-query xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <D:prop>
                    <C:calendar-data/>
                </D:prop>
                <C:filter>
                    <C:comp-filter name="VCALENDAR">
                        <C:comp-filter name="VEVENT">
                            <C:prop-filter name="ATTENDEE">
                                <C:text-match collation="i;ascii-casemap">mailto:lisa@example.com</C:text-match>
                                <C:param-filter name="PARTSTAT">
                                    <C:text-match collation="i;ascii-casemap">DECLINED</C:text-match>
                                </C:param-filter>
                            </C:prop-filter>
                        </C:comp-filter>
                    </C:comp-filter>
                </C:filter>
            </C:calendar-query>
            """;

        assertXmlSimilar(expected, xml);
    }

    private static void assertXmlSimilar(String expected, String actual) {
        Diff diff = DiffBuilder
            .compare(expected)
            .withTest(actual)
            .ignoreWhitespace()
            .checkForSimilar()
            .build();

        assertThat(diff.hasDifferences())
            .as(diff.toString())
            .isFalse();
    }

    @Test
    void shouldGenerateCalendarQueryReportWithMultiplePropFilters() throws Exception {
        Instant dtStampEnd = Instant.parse("2027-01-01T00:00:00Z");
        Instant dtStartEnd = Instant.parse("2026-12-31T23:59:59Z");
        Username attendee = Username.of("lisa@example.com");

        CalendarQuery query = CalendarQuery.ofFilters(
            TimeRangePropFilter.dtStampBefore(dtStampEnd),
            TimeRangePropFilter.dtStartBefore(dtStartEnd),
            AttendeePropFilter.declined(attendee)
        );

        String xml = query.toCalendarQueryReport();

        String expected = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <C:calendar-query xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:D="DAV:">
                <D:prop>
                    <C:calendar-data/>
                </D:prop>
                <C:filter>
                    <C:comp-filter name="VCALENDAR">
                        <C:comp-filter name="VEVENT">
                            <C:prop-filter name="DTSTAMP">
                                <C:time-range end="20270101T000000Z"/>
                            </C:prop-filter>
                            <C:prop-filter name="DTSTART">
                                <C:time-range end="20261231T235959Z"/>
                            </C:prop-filter>
                            <C:prop-filter name="ATTENDEE">
                                <C:text-match collation="i;ascii-casemap">mailto:lisa@example.com</C:text-match>
                                <C:param-filter name="PARTSTAT">
                                    <C:text-match collation="i;ascii-casemap">DECLINED</C:text-match>
                                </C:param-filter>
                            </C:prop-filter>
                        </C:comp-filter>
                    </C:comp-filter>
                </C:filter>
            </C:calendar-query>
            """;

        assertXmlSimilar(expected, xml);
    }
}
