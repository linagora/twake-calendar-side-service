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

package com.linagora.calendar.webadmin;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.james.util.DurationParser;

import com.google.common.base.Preconditions;
import com.linagora.calendar.webadmin.model.EventArchivalCriteria;

import spark.Request;

public class EventArchivalCriteriaRequestParser {
    private static final String CREATED_BEFORE_PARAM = "createdBefore";
    private static final String LAST_MODIFIED_BEFORE_PARAM = "lastModifiedBefore";
    private static final String MASTER_DTSTART_BEFORE_PARAM = "masterDtStartBefore";
    private static final String IS_REJECTED_PARAM = "isRejected";
    private static final String NON_RECURRING_PARAM = "isNotRecurring";

    public static EventArchivalCriteria extractEventArchivalCriteria(Request request, Clock clock) {
        EventArchivalCriteria.Builder builder = EventArchivalCriteria.builder();

        extractInstantBefore(request, CREATED_BEFORE_PARAM, clock)
            .ifPresent(builder::createdBefore);

        extractInstantBefore(request, LAST_MODIFIED_BEFORE_PARAM, clock)
            .ifPresent(builder::lastModifiedBefore);

        extractInstantBefore(request, MASTER_DTSTART_BEFORE_PARAM, clock)
            .ifPresent(builder::masterDtStartBefore);

        extractBoolean(request, IS_REJECTED_PARAM)
            .ifPresent(builder::rejectedOnly);

        extractBoolean(request, NON_RECURRING_PARAM)
            .ifPresent(builder::nonRecurring);

        return builder.build();
    }

    private static Optional<Instant> extractInstantBefore(Request request, String paramName, Clock clock) {
        return Optional.ofNullable(request.queryParams(paramName))
            .map(rawValue -> DurationParser.parse(rawValue, ChronoUnit.YEARS))
            .map(duration -> {
                Preconditions.checkArgument(!duration.isZero() && !duration.isNegative(), "`" + paramName + "` must be positive");
                return clock.instant().minus(duration);
            });
    }

    private static Optional<Boolean> extractBoolean(Request request, String paramName) {
        return Optional.ofNullable(request.queryParams(paramName))
            .map(value -> {
                Boolean parsed = BooleanUtils.toBooleanObject(value);
                Preconditions.checkArgument(parsed != null, "`" + paramName + "` must be a boolean");
                return parsed;
            });
    }
}
