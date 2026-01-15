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

package com.linagora.calendar.webadmin.model;

import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Username;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.model.CalendarQuery;
import com.linagora.calendar.dav.model.CalendarQuery.AttendeePropFilter;

public record EventArchivalCriteria(Optional<Instant> createdBefore,
                                    Optional<Instant> lastModifiedBefore,
                                    Optional<Instant> masterDtStartBefore,
                                    boolean rejectedOnly,
                                    boolean isNotRecurring) {
    public static EventArchivalCriteria.Builder builder() {
        return new EventArchivalCriteria.Builder();
    }

    public CalendarQuery toCalendarQuery(Username targetUser) {
        ImmutableList.Builder<CalendarQuery.PropFilter> filters = ImmutableList.builder();

        createdBefore.ifPresent(instant ->
            filters.add(CalendarQuery.TimeRangePropFilter.dtStampBefore(instant)));

        lastModifiedBefore.ifPresent(instant ->
            filters.add(CalendarQuery.TimeRangePropFilter.lastModifiedBefore(instant)));

        masterDtStartBefore.ifPresent(instant ->
            filters.add(CalendarQuery.TimeRangePropFilter.dtStartBefore(instant)));

        if (rejectedOnly) {
            filters.add(AttendeePropFilter.declined(targetUser));
        }

        if (isNotRecurring) {
            filters.addAll(CalendarQuery.IsNotDefinedPropFilter.isNotRecurring());
        }

        return CalendarQuery.ofFilters(filters.build());
    }

    public static final class Builder {
        private Optional<Instant> createdBefore = Optional.empty();
        private Optional<Instant> lastModifiedBefore = Optional.empty();
        private Optional<Instant> masterDtStartBefore = Optional.empty();
        private boolean rejectedOnly = false;
        private boolean nonRecurring = false;

        private Builder() {
        }

        public EventArchivalCriteria.Builder createdBefore(Instant instant) {
            this.createdBefore = Optional.ofNullable(instant);
            return this;
        }

        public EventArchivalCriteria.Builder lastModifiedBefore(Instant instant) {
            this.lastModifiedBefore = Optional.ofNullable(instant);
            return this;
        }

        public EventArchivalCriteria.Builder masterDtStartBefore(Instant instant) {
            this.masterDtStartBefore = Optional.ofNullable(instant);
            return this;
        }

        public EventArchivalCriteria.Builder rejectedOnly(boolean rejectedOnly) {
            this.rejectedOnly = rejectedOnly;
            return this;
        }

        public EventArchivalCriteria.Builder nonRecurring(boolean nonRecurring) {
            this.nonRecurring = nonRecurring;
            return this;
        }

        public EventArchivalCriteria build() {
            return new EventArchivalCriteria(
                createdBefore,
                lastModifiedBefore,
                masterDtStartBefore,
                rejectedOnly,
                nonRecurring);
        }
    }

}