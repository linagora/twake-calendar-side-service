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

package com.linagora.calendar.twakespace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpaceEventTypeTest {
    @Test
    void fromRoutingKeyShouldReadTheRoutingKeyOfASpaceEvent() {
        assertThat(SpaceEventType.fromRoutingKey("twake.space.member.role.changed")).contains(SpaceEventType.MEMBER_ROLE_CHANGED);
    }

    @Test
    void fromRoutingKeyShouldBeEmptyForAnotherRoutingKey() {
        assertThat(SpaceEventType.fromRoutingKey("twake.space.archived")).isEmpty();
    }
}
