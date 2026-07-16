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

package com.linagora.calendar.storage.booking;

import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

public class BookingLinkExtraAttendeeUtil {

    public static final int MAX_EXTRA_ATTENDEES = 20;

    public static List<OpenPaaSId> parse(Optional<List<String>> raw) {
        return raw.map(BookingLinkExtraAttendeeUtil::parse)
            .orElse(BookingLinkInsertRequest.NO_EXTRA_ATTENDEE);
    }

    public static List<OpenPaaSId> parse(List<String> raw) {
        List<OpenPaaSId> parsed = raw.stream()
            .map(StringUtils::trimToEmpty)
            .peek(value -> Preconditions.checkArgument(!value.isEmpty(), "'extraAttendees' must not contain blank values"))
            .map(OpenPaaSId::new)
            .distinct()
            .toList();
        Preconditions.checkArgument(parsed.size() <= MAX_EXTRA_ATTENDEES,
            "'extraAttendees' must not contain more than %s entries", MAX_EXTRA_ATTENDEES);
        return parsed;
    }

    private BookingLinkExtraAttendeeUtil() {
    }
}
