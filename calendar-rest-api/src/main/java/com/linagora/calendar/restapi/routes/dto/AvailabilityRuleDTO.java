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

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.linagora.calendar.api.booking.AvailabilityRule;
import com.linagora.calendar.restapi.AvailabilityRuleType;
import com.linagora.calendar.restapi.DayOfWeekUtil;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record AvailabilityRuleDTO(@JsonProperty("type") AvailabilityRuleType type,
                                  @JsonProperty("dayOfWeek") Optional<String> dayOfWeek,
                                  @JsonProperty("start") String start,
                                  @JsonProperty("end") String end,
                                  @JsonProperty("timeZone") Optional<String> timeZone) {

    private static final DateTimeFormatter LOCAL_DATE_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Optional<String> NO_DAY_OF_WEEK = Optional.empty();

    public static AvailabilityRuleDTO from(AvailabilityRule rule) {
        return switch (rule) {
            case AvailabilityRule.WeeklyAvailabilityRule weekly -> AvailabilityRuleDTO.fromWeekly(weekly);
            case AvailabilityRule.FixedAvailabilityRule fixed -> AvailabilityRuleDTO.fromFixed(fixed);
        };
    }

    public static AvailabilityRuleDTO fromWeekly(AvailabilityRule.WeeklyAvailabilityRule rule) {
        return new AvailabilityRuleDTO(AvailabilityRuleType.WEEKLY, Optional.of(DayOfWeekUtil.toAbbreviation(rule.dayOfWeek())),
            rule.start().toString(), rule.end().toString(), rule.timeZone().map(ZoneId::getId));
    }

    public static AvailabilityRuleDTO fromFixed(AvailabilityRule.FixedAvailabilityRule rule) {
        return new AvailabilityRuleDTO(AvailabilityRuleType.FIXED, NO_DAY_OF_WEEK,
            rule.start().toLocalDateTime().format(LOCAL_DATE_TIME_FORMAT),
            rule.end().toLocalDateTime().format(LOCAL_DATE_TIME_FORMAT),
            Optional.of(rule.start().getZone().getId()));
    }

    public AvailabilityRule toAvailabilityRule(ZoneId defaultTimeZone) {
        Preconditions.checkArgument(!Objects.isNull(type), "'type' is required in availability rule");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(start), "'start' is required in availability rule");
        Preconditions.checkArgument(!Strings.isNullOrEmpty(end), "'end' is required in availability rule");
        ZoneId effectiveTimeZone = resolveTimeZone(defaultTimeZone);
        return switch (type) {
            case WEEKLY -> {
                DayOfWeek dayOfWeekObject = getDayOfWeek();
                try {
                    yield new AvailabilityRule.WeeklyAvailabilityRule(dayOfWeekObject, LocalTime.parse(start), LocalTime.parse(end), effectiveTimeZone);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid 'start' or 'end' time format for weekly rule, expected HH:mm", e);
                }
            }
            case FIXED -> {
                try {
                    ZonedDateTime startDateTime = LocalDateTime.parse(start).atZone(effectiveTimeZone);
                    ZonedDateTime endDateTime = LocalDateTime.parse(end).atZone(effectiveTimeZone);
                    if (startDateTime.isAfter(endDateTime) || startDateTime.isEqual(endDateTime)) {
                        throw new IllegalArgumentException("'start' must be before 'end' for fixed rule");
                    }
                    yield new AvailabilityRule.FixedAvailabilityRule(startDateTime, endDateTime);
                } catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Invalid 'start' or 'end' date-time format for fixed rule, expected yyyy-MM-ddTHH:mm:ss", e);
                }
            }
        };
    }

    private ZoneId resolveTimeZone(ZoneId defaultTimeZone) {
        return timeZone.map(tz -> {
            try {
                return ZoneId.of(tz);
            } catch (DateTimeException e) {
                throw new IllegalArgumentException("Invalid 'timeZone' format: " + tz, e);
            }
        }).orElse(defaultTimeZone);
    }

    private DayOfWeek getDayOfWeek() {
        return DayOfWeekUtil.fromAbbreviation(dayOfWeek.orElseThrow(() -> new IllegalArgumentException("'dayOfWeek' must be provided for weekly rule")));
    }
}
