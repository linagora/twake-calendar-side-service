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

package com.linagora.calendar.twakespace.model;

import java.util.Arrays;
import java.util.Optional;

public enum SpaceEventType {
    CREATED("twake.space.created"),
    UPDATED("twake.space.updated"),
    DELETED("twake.space.deleted"),
    MEMBER_ADDED("twake.space.member.added"),
    MEMBER_ROLE_CHANGED("twake.space.member.role.changed"),
    MEMBER_REMOVED("twake.space.member.removed");

    public static Optional<SpaceEventType> fromRoutingKey(String routingKey) {
        return Arrays.stream(values())
            .filter(type -> type.routingKey.equals(routingKey))
            .findFirst();
    }

    private final String routingKey;

    SpaceEventType(String routingKey) {
        this.routingKey = routingKey;
    }

    public String routingKey() {
        return routingKey;
    }
}
