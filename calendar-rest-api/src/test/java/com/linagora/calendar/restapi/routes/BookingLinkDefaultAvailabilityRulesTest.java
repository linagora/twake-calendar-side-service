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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.configuration.resolver.BusinessHoursSettingReader;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader;

class BookingLinkDefaultAvailabilityRulesTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final LocalTime EIGHT = LocalTime.of(8, 0);
    private static final LocalTime NINETEEN = LocalTime.of(19, 0);

    @ParameterizedTest
    @ValueSource(strings = {
        "[]",
        "[null]",
        "[{\"start\":\"8:0\",\"end\":\"19:0\",\"daysOfWeek\":[0]}]",
        "[{\"start\":\"8:0\",\"end\":\"19:0\",\"daysOfWeek\":[-5]}]",
        "[{\"start\":\"8:0\",\"end\":\"19:0\",\"daysOfWeek\":[8]}]",
        "[{\"start\":\"8:0\",\"end\":\"19:0\",\"daysOfWeek\":[null]}]",
        "[{\"start\":\"8:0\",\"end\":\"19:0\"}]",
        "[{\"start\":\"24:00\",\"end\":\"19:0\",\"daysOfWeek\":[1]}]",
        "[{\"start\":\"0816:45\",\"end\":\"19:0\",\"daysOfWeek\":[1]}]",
        "[{\"start\":\"08\",\"end\":\"19:0\",\"daysOfWeek\":[1]}]",
        "[{\"start\":\"8:0\",\"daysOfWeek\":[1]}]",
        "[{\"end\":\"19:0\",\"daysOfWeek\":[1]}]",
        "[{\"start\":\"19:0\",\"end\":\"8:0\",\"daysOfWeek\":[1]}]"
    })
    void defaultAvailabilityRulesShouldIgnoreInvalidStoredBusinessHours(String businessHours) throws Exception {
        assertThat(BookingLinkCreateRoute.getDefaultAvailabilityRules(settings(businessHours)))
            .isEmpty();
    }

    @Test
    void defaultAvailabilityRulesShouldKeepValidDays() throws Exception {
        Optional<AvailabilityRules> rules = BookingLinkCreateRoute.getDefaultAvailabilityRules(
            settings("[{\"start\":\"8:0\",\"end\":\"19:0\",\"daysOfWeek\":[0,1,null,7,8]}]"));

        assertThat(rules).contains(new AvailabilityRules(List.of(
            weekly(DayOfWeek.MONDAY, EIGHT, NINETEEN),
            weekly(DayOfWeek.SUNDAY, EIGHT, NINETEEN))));
    }

    @Test
    void defaultAvailabilityRulesShouldKeepValidEntries() throws Exception {
        Optional<AvailabilityRules> rules = BookingLinkCreateRoute.getDefaultAvailabilityRules(
            settings("[{\"start\":\"08\",\"end\":\"19:0\",\"daysOfWeek\":[1]},{\"start\":\"9:30\",\"end\":\"17:0\",\"daysOfWeek\":[2]}]"));

        assertThat(rules).contains(new AvailabilityRules(List.of(
            weekly(DayOfWeek.TUESDAY, LocalTime.of(9, 30), LocalTime.of(17, 0)))));
    }

    @Test
    void defaultAvailabilityRulesShouldBeEmptyWhenNoStoredBusinessHours() {
        SettingsBasedResolver.ResolvedSettings settings = new SettingsBasedResolver.ResolvedSettings(Map.of(
            TimeZoneSettingReader.TIMEZONE_IDENTIFIER, PARIS));

        assertThat(BookingLinkCreateRoute.getDefaultAvailabilityRules(settings)).isEmpty();
    }

    private static AvailabilityRule weekly(DayOfWeek day, LocalTime start, LocalTime end) {
        return new AvailabilityRule.WeeklyAvailabilityRule(day, start, end, PARIS);
    }

    private static SettingsBasedResolver.ResolvedSettings settings(String businessHours) throws Exception {
        List<BusinessHoursSettingReader.BusinessHoursDto> dtos = new BusinessHoursSettingReader()
            .parse(new ObjectMapper().readTree(businessHours)).orElseThrow();
        return new SettingsBasedResolver.ResolvedSettings(Map.of(
            TimeZoneSettingReader.TIMEZONE_IDENTIFIER, PARIS,
            BusinessHoursSettingReader.BUSINESS_HOURS_IDENTIFIER, dtos));
    }
}
