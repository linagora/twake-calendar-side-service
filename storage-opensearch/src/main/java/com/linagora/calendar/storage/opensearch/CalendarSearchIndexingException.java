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

package com.linagora.calendar.storage.opensearch;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.vacation.api.AccountId;

import com.linagora.calendar.storage.eventsearch.EventUid;

public class CalendarSearchIndexingException extends RuntimeException {

    public static CalendarSearchIndexingException of(String message, AccountId accountId, EventUid eventUid, Throwable cause) {
        return new CalendarSearchIndexingException(message, accountId, Optional.of(eventUid), cause);
    }

    public static CalendarSearchIndexingException of(String message, AccountId accountId, Throwable cause) {
        return new CalendarSearchIndexingException(message, accountId, Optional.empty(), cause);
    }

    public CalendarSearchIndexingException(String message, AccountId accountId, Optional<EventUid> eventUid, Throwable cause) {
        super(message + ". AccountId = " + accountId.getIdentifier() +
            eventUid.map(v -> ", eventUid = " + v.value()).orElse(StringUtils.EMPTY), cause);
    }
}