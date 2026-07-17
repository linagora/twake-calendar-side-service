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
import java.util.stream.Stream;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

/**
 * Node of the extra attendee tree of a booking link.
 *
 * <p>The tree shape leaves room for richer combinations (optional participants, substitutes: bob OR michael but
 * only one of them). Today only an {@link And} of {@link Participant} leaves can be expressed through the APIs,
 * which carries the historical semantic: invite all of them.
 */
public sealed interface ExtraAttendeeNode {

    record Participant(OpenPaaSId id) implements ExtraAttendeeNode {
        public Participant {
            Preconditions.checkNotNull(id, "'participant' must not be null");
        }

        @Override
        public Stream<OpenPaaSId> participants() {
            return Stream.of(id);
        }
    }

    record And(List<ExtraAttendeeNode> children) implements ExtraAttendeeNode {
        public And {
            Preconditions.checkNotNull(children, "'and' must not be null");
            children = List.copyOf(children);
        }

        @Override
        public Stream<OpenPaaSId> participants() {
            return children.stream().flatMap(ExtraAttendeeNode::participants);
        }
    }

    Stream<OpenPaaSId> participants();
}
