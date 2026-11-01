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

package com.linagora.calendar.restapi.routes.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.api.booking.AvailabilityRule;

class AvailabilityRuleDTOTest {
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @ParameterizedTest
    @ValueSource(strings = {"2026-11-02T10:00:00", "2026-11-02T10:00:00.5", "2026-11-02T10:00:00.999999999",
        "0000-06-01T10:00:00", "-0001-06-01T10:00:00", "-10000-01-01T10:00:00", "+10000-01-01T10:00:00"})
    void fixedRuleShouldSurviveGetRepresentation(String start) {
        ZonedDateTime from = LocalDateTime.parse(start).atZone(PARIS);
        AvailabilityRule.FixedAvailabilityRule rule = new AvailabilityRule.FixedAvailabilityRule(from, from.plusHours(2));

        assertThat(AvailabilityRuleDTO.fromFixed(rule).toAvailabilityRule(PARIS)).isEqualTo(rule);
    }

    @Test
    void fixedRuleShouldKeepWholeSecondsFormat() {
        ZonedDateTime from = LocalDateTime.parse("2026-01-26T02:00:00").atZone(PARIS);
        AvailabilityRule.FixedAvailabilityRule rule = new AvailabilityRule.FixedAvailabilityRule(from, from.plusHours(2));

        AvailabilityRuleDTO dto = AvailabilityRuleDTO.fromFixed(rule);

        assertThat(dto.start()).isEqualTo("2026-01-26T02:00:00");
        assertThat(dto.end()).isEqualTo("2026-01-26T04:00:00");
    }
}
