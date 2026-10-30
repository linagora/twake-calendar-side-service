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

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class TwakeSpaceTest {
    private static final Instant T1 = Instant.parse("2026-10-06T10:00:00Z");
    private static final Instant T2 = T1.plusSeconds(1);
    private static final Instant T3 = T1.plusSeconds(2);
    private static final Username ALICE = Username.of("alice@space.tld");
    private static final Username BOB = Username.of("bob@space.tld");
    private static final TwakeSpace SPACE = TwakeSpace.of(new SpaceId("space-1"), Optional.of(new OrganizationId("org")), Optional.of(Domain.of("space.tld")));

    private static final List<TwakeSpace> EVENTS = List.of(
        SPACE.withName(new TwakeSpace.Name("Marketing", T1))
            .withMembers(Map.of(ALICE, TwakeSpace.Membership.role("admin", T1), BOB, TwakeSpace.Membership.role("editor", T1))),
        SPACE.withMembers(Map.of(BOB, TwakeSpace.Membership.role("viewer", T2))),
        SPACE.withMembers(Map.of(BOB, TwakeSpace.Membership.removed(T3))),
        SPACE.withName(new TwakeSpace.Name("Sales", T2)),
        SPACE.withMembers(Map.of(ALICE, TwakeSpace.Membership.role("viewer", T2))),
        SPACE.withDeletion(T3),
        SPACE.withDeletion(T2));

    private static final TwakeSpace EXPECTED = SPACE.withName(new TwakeSpace.Name("Sales", T2))
        .withMembers(Map.of(ALICE, TwakeSpace.Membership.role("viewer", T2), BOB, TwakeSpace.Membership.removed(T3)))
        .withDeletion(T2);

    @RepeatedTest(50)
    void mergingEventsShouldGiveTheSameSpaceWhateverTheirOrder() {
        List<TwakeSpace> events = new ArrayList<>(EVENTS);
        Collections.shuffle(events, new Random());

        assertThat(events.stream().reduce(TwakeSpace::merge)).contains(EXPECTED);
    }

    @Test
    void mergingAnEventTwiceShouldChangeNothing() {
        assertThat(EXPECTED.merge(EVENTS.get(1))).isEqualTo(EXPECTED);
    }

    @Test
    void removalShouldWinOverARoleOfTheSameTime() {
        TwakeSpace removed = SPACE.withMembers(Map.of(BOB, TwakeSpace.Membership.removed(T1)));
        TwakeSpace added = SPACE.withMembers(Map.of(BOB, TwakeSpace.Membership.role("editor", T1)));

        assertThat(removed.merge(added)).isEqualTo(removed);
        assertThat(added.merge(removed)).isEqualTo(removed);
    }

    @Test
    void organizationAndDomainShouldBeTakenFromTheChangeWhenUnknown() {
        TwakeSpace unknown = TwakeSpace.of(SPACE.id(), Optional.empty(), Optional.empty());

        assertThat(unknown.merge(SPACE)).isEqualTo(SPACE);
    }
}
