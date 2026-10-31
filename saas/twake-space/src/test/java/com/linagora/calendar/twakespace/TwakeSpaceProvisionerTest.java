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

import java.time.Instant;
import java.util.List;

import org.apache.james.core.Username;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linagora.calendar.twakespace.model.OrganizationId;
import com.linagora.calendar.twakespace.model.SpaceEvent;
import com.linagora.calendar.twakespace.model.SpaceEventType;
import com.linagora.calendar.twakespace.model.SpaceId;
import com.linagora.calendar.twakespace.model.TwakeSpace;

class TwakeSpaceProvisionerTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-10-06T10:00:00Z");
    private static final Username ALICE = Username.of("alice@space.tld");

    @ParameterizedTest
    @EnumSource(value = SpaceEventType.class, names = {"CREATED", "MEMBER_ADDED", "MEMBER_ROLE_CHANGED"})
    void aMemberWithoutRoleShouldBeAViewer(SpaceEventType type) {
        SpaceEvent event = new SpaceEvent(new OrganizationId("org"), "space.tld", new SpaceId("space-1"), "Space",
            List.of(new SpaceEvent.Member(ALICE.asString(), null)), TIMESTAMP);

        TwakeSpace space = TwakeSpaceProvisioner.change(type, event);

        assertThat(space.members().get(ALICE).role()).contains(TwakeSpaceProvisioner.VIEWER);
    }

    @ParameterizedTest
    @EnumSource(value = SpaceEventType.class, names = {"CREATED", "MEMBER_ADDED", "MEMBER_ROLE_CHANGED"})
    void aMemberRoleShouldBeKept(SpaceEventType type) {
        SpaceEvent event = new SpaceEvent(new OrganizationId("org"), "space.tld", new SpaceId("space-1"), "Space",
            List.of(new SpaceEvent.Member(ALICE.asString(), "editor")), TIMESTAMP);

        TwakeSpace space = TwakeSpaceProvisioner.change(type, event);

        assertThat(space.members().get(ALICE).role()).contains("editor");
    }
}
