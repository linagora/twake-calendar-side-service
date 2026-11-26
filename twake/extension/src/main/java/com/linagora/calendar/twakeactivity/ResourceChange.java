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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.linagora.calendar.twakeactivity.UserActivity.Type;

// A resource sabre reports as created. The change tag is the etag sabre gives the resource.
public record ResourceChange(Type type, ResourcePath resource, String changeTag) {
    // Named after what sabre says of the change, so that a redelivered message publishes the same id.
    public String id() {
        return UUID.nameUUIDFromBytes(String.join(":", type.cloudEventType(), resource.path(), changeTag)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
