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

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

/**
 * Extra attendees of a booking link, held as a tree so that richer combinations can be modelled later on.
 *
 * @see ExtraAttendeeNode
 */
public record ExtraAttendees(ExtraAttendeeNode root) {

    public static final ExtraAttendees NONE = of(List.of());

    public static ExtraAttendees of(OpenPaaSId... participants) {
        return of(List.of(participants));
    }

    public static ExtraAttendees of(List<OpenPaaSId> participants) {
        return new ExtraAttendees(new ExtraAttendeeNode.And(participants.stream()
            .<ExtraAttendeeNode>map(ExtraAttendeeNode.Participant::new)
            .toList()));
    }

    public ExtraAttendees {
        Preconditions.checkNotNull(root, "'extraAttendees' must not be null");
    }

    /**
     * Flat view of the tree: the users to invite.
     */
    public List<OpenPaaSId> participants() {
        return root.participants().toList();
    }

    public boolean isEmpty() {
        return participants().isEmpty();
    }
}
