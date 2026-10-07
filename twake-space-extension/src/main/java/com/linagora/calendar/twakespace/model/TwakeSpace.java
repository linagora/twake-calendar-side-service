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

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BinaryOperator;
import java.util.stream.Stream;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.google.common.collect.ImmutableMap;

// What the events of a space say about it. Merging keeps the latest value of each field, by event timestamp,
// so that the events of a space give the same space whatever order they are handled in.
public record TwakeSpace(SpaceId id, Optional<OrganizationId> organization, Optional<Domain> domain, Optional<Name> name,
                         Map<Username, Membership> members, Optional<Instant> deletion) {
    public record Name(String value, Instant timestamp) {
        private static final Comparator<Name> LATEST = Comparator.comparing(Name::timestamp)
            .thenComparing(Name::value);
    }

    // A removed member has no role. Removal is kept, so that an older event cannot add the member back.
    public record Membership(Optional<String> role, Instant timestamp) {
        // On equal timestamps the removal wins, then the alphabetically first role.
        private static final Comparator<Membership> LATEST = Comparator.comparing(Membership::timestamp)
            .thenComparing(membership -> membership.role().orElse(""), Comparator.reverseOrder());

        public static Membership role(String role, Instant timestamp) {
            return new Membership(Optional.of(role), timestamp);
        }

        public static Membership removed(Instant timestamp) {
            return new Membership(Optional.empty(), timestamp);
        }
    }

    public static TwakeSpace of(SpaceId id, Optional<OrganizationId> organization, Optional<Domain> domain) {
        return new TwakeSpace(id, organization, domain, Optional.empty(), Map.of(), Optional.empty());
    }

    public TwakeSpace {
        members = ImmutableMap.copyOf(members);
    }

    public TwakeSpace withName(Name name) {
        return new TwakeSpace(id, organization, domain, Optional.of(name), members, deletion);
    }

    public TwakeSpace withMembers(Map<Username, Membership> members) {
        return new TwakeSpace(id, organization, domain, name, members, deletion);
    }

    public TwakeSpace withDeletion(Instant deletion) {
        return new TwakeSpace(id, organization, domain, name, members, Optional.of(deletion));
    }

    // The organization and domain of a space do not change: the first known stays.
    public TwakeSpace merge(TwakeSpace other) {
        Map<Username, Membership> mergedMembers = new HashMap<>(members);
        other.members.forEach((username, membership) -> mergedMembers.merge(username, membership, BinaryOperator.maxBy(Membership.LATEST)));
        return new TwakeSpace(id,
            organization.or(other::organization),
            domain.or(other::domain),
            latest(name, other.name, Name.LATEST),
            mergedMembers,
            latest(deletion, other.deletion, Comparator.<Instant>reverseOrder()));
    }

    public boolean isDeleted() {
        return deletion.isPresent();
    }

    private static <T> Optional<T> latest(Optional<T> first, Optional<T> second, Comparator<T> order) {
        return Stream.concat(first.stream(), second.stream()).max(order);
    }
}
