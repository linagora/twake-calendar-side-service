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

package com.linagora.calendar.api;

import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;

/**
 * @param recurrenceId when present, the answer is for this occurrence of the recurring event only, otherwise for
 *                     the whole event
 */
public record Participation(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI,
                            ParticipantAction action, Optional<String> recurrenceId) {

    public enum ParticipantAction {
        ACCEPTED, REJECTED, TENTATIVE
    }

    public Participation {
        Preconditions.checkNotNull(organizer, "Organizer must not be null");
        Preconditions.checkNotNull(attendee, "Attendee must not be null");
        Preconditions.checkArgument(StringUtils.isNotEmpty(eventUid), "Event uid must not be null");
        Preconditions.checkArgument(StringUtils.isNotEmpty(calendarURI), "Calendar URI must not be empty");
        Preconditions.checkNotNull(action, "Action must not be null");
        Preconditions.checkNotNull(recurrenceId, "Recurrence id must not be null");
    }

    public Participation(MailAddress organizer, MailAddress attendee, String eventUid, String calendarURI, ParticipantAction action) {
        this(organizer, attendee, eventUid, calendarURI, action, Optional.empty());
    }

    public Participation withAction(ParticipantAction newAction) {
        return new Participation(organizer, attendee, eventUid, calendarURI, newAction, recurrenceId);
    }

    public ParticipationGrant grant() {
        return new ParticipationGrant(organizer, attendee, eventUid, calendarURI, recurrenceId);
    }
}
