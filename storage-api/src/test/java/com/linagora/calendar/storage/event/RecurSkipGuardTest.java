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


package com.linagora.calendar.storage.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import net.fortuna.ical4j.model.Recur;

class RecurSkipGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=MINUTELY;BYHOUR=0",
        "FREQ=DAILY;BYDAY=MO",
        "FREQ=DAILY;BYMONTH=1",
        "FREQ=MONTHLY;BYMONTHDAY=31",
        "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
        "FREQ=WEEKLY;BYDAY=MO;BYSETPOS=1",
        "FREQ=SECONDLY;BYSECOND=0"
    })
    void mayLeavePeriodsEmptyShouldDetectFilteringParts(String rule) {
        assertThat(RecurSkipGuard.mayLeavePeriodsEmpty(new Recur<>(rule))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=DAILY",
        "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR",
        "FREQ=YEARLY;BYMONTH=1,6",
        "FREQ=DAILY;BYHOUR=9,14;BYMINUTE=0,30",
        "FREQ=HOURLY;BYMINUTE=0,30"
    })
    void mayLeavePeriodsEmptyShouldAcceptExpandingParts(String rule) {
        assertThat(RecurSkipGuard.mayLeavePeriodsEmpty(new Recur<>(rule))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=YEARLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYHOUR=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23;BYMINUTE=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59;BYSECOND=0,1,2",
        "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR,SA,SU;BYHOUR=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23;BYMINUTE=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59;BYSECOND=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29"
    })
    void canExpandShouldCountTheDaysBYDAYStandsFor(String rule) {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");

        assertThat(RecurSkipGuard.canExpand(new Recur<>(rule), start, start, start)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=MINUTELY;COUNT=1000;BYHOUR=0;BYSECOND=0,1,2,3,4,5,6,7,8,9",
        "FREQ=HOURLY;COUNT=10;BYDAY=MO;BYMINUTE=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29"
    })
    void canExpandShouldNotBoundTheWalkByCountWhenPeriodsMayBeEmpty(String rule) {
        Instant seed = Instant.parse("2000-01-01T00:00:00Z");
        Instant target = Instant.parse("2026-04-08T10:00:00Z");

        assertThat(RecurSkipGuard.canExpand(new Recur<>(rule), seed, target, target)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=WEEKLY;COUNT=81920;BYDAY=MO,TU,WE,TH,FR",
        "FREQ=DAILY;COUNT=5000;BYDAY=MO,TU,WE,TH,FR",
        "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU",
        "FREQ=MONTHLY;BYMONTHDAY=15;COUNT=24"
    })
    void canExpandShouldAcceptRealWorldRules(String rule) {
        Instant seed = Instant.parse("2000-01-01T00:00:00Z");
        Instant target = Instant.parse("2026-04-08T10:00:00Z");

        assertThat(RecurSkipGuard.canExpand(new Recur<>(rule), seed, target, target)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=HOURLY;BYWEEKNO=1;BYMINUTE=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59;BYSECOND=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31",
        "FREQ=SECONDLY;BYWEEKNO=20"
    })
    void canExpandShouldCountTheYearIcal4jWalksForByweeknoBelowYearly(String rule) {
        Instant start = Instant.parse("2026-04-08T10:00:00Z");

        assertThat(RecurSkipGuard.canExpand(new Recur<>(rule), start, start, start)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "FREQ=WEEKLY;BYDAY=1MO",
        "FREQ=DAILY;BYDAY=-1FR",
        "FREQ=HOURLY;BYDAY=1MO;BYMINUTE=0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59;BYSECOND=0,1",
        "FREQ=DAILY;BYWEEKNO=20",
        "FREQ=MINUTELY;BYWEEKNO=20",
        "FREQ=YEARLY;BYWEEKNO=1;BYDAY=MO",
        "FREQ=MONTHLY;BYDAY=1MO",
        "FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"
    })
    void canExpandShouldAcceptCheapRulesMappingCandidatesBack(String rule) {
        Instant start = Instant.parse("2026-04-08T10:00:00Z");

        assertThat(RecurSkipGuard.canExpand(new Recur<>(rule), start, start, start)).isTrue();
    }
}
