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

package com.linagora.calendar.twakeactivity;

import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;

// Who made the change, and the user owning the calendar or the address book it was made in: the same user unless the
// calendar or the address book is delegated. A calendar or an address book of a domain or a resource has no owner.
public record Actors(Username actor, Optional<Username> resourceOwner) {
    public Optional<Domain> domain() {
        return actor.getDomainPart();
    }
}
