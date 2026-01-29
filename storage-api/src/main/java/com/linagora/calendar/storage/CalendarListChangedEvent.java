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

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.events.Event;

import com.google.common.base.Preconditions;

public record CalendarListChangedEvent(Event.EventId eventId,
                                       Username username,
                                       CalendarURL calendarURL,
                                       ChangeType changeType) implements Event {

    public static CalendarListChangedEvent of(Username username,
                                              CalendarURL calendarURL,
                                              ChangeType changeType) {
        return new CalendarListChangedEvent(Event.EventId.random(), username, calendarURL, changeType);
    }

    public enum ChangeType {
        CREATED,
        UPDATED,
        DELETED,
        DELEGATED,
        SUBSCRIBED,
        RIGHTS_REVOKED;

        public static ChangeType parse(String rawString) {
            Preconditions.checkArgument(StringUtils.isNotBlank(rawString), "ChangeType must not be blank");
            for (ChangeType changeType : ChangeType.values()) {
                if (changeType.name().equalsIgnoreCase(rawString)) {
                    return changeType;
                }
            }
            throw new IllegalArgumentException("Unknown ChangeType: " + rawString);
        }
    }

    @Override
    public Username getUsername() {
        return username;
    }

    @Override
    public boolean isNoop() {
        return false;
    }

    @Override
    public EventId getEventId() {
        return eventId;
    }
}
