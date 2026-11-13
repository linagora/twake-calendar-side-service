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

package com.linagora.calendar.restapi.routes;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Resolves the extra attendees of a booking link, which are stored as OpenPaaS ids of registered users.
 */
public class BookingLinkExtraAttendeeResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkExtraAttendeeResolver.class);

    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public BookingLinkExtraAttendeeResolver(OpenPaaSUserDAO openPaaSUserDAO) {
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    /**
     * Resolves the extra attendees, failing on any of them that is not a registered user of the owner's domain.
     * A user of another domain is reported exactly like an unknown one, so that this check does not tell
     * whether an account exists in another tenant.
     */
    public Flux<OpenPaaSUser> resolve(Username owner, List<OpenPaaSId> extraAttendees) {
        return Flux.fromIterable(extraAttendees)
            .concatMap(id -> openPaaSUserDAO.retrieve(id)
                .filter(user -> sameDomain(owner, user))
                .switchIfEmpty(Mono.error(() -> unknownUser(id))));
    }

    /**
     * Resolves the extra attendees that still exist, skipping the others: a user deleted after the booking link
     * was created must not make the link unbookable. Users outside the owner's domain are skipped too, so that a
     * booking link stored before that rule was enforced neither publishes them nor invites them.
     */
    public Flux<OpenPaaSUser> resolveExisting(Username owner, List<OpenPaaSId> extraAttendees) {
        return Flux.fromIterable(extraAttendees)
            .concatMap(id -> openPaaSUserDAO.retrieve(id)
                .switchIfEmpty(Mono.fromRunnable(() ->
                    LOGGER.warn("Skipping extra attendee {}: no such user anymore", id.value())))
                .filter(user -> isSameDomainOrWarn(owner, user)));
    }

    public Mono<Void> validate(Username owner, List<OpenPaaSId> extraAttendees) {
        if (extraAttendees.isEmpty()) {
            return Mono.empty();
        }
        return resolve(owner, extraAttendees)
            .map(OpenPaaSUser::id)
            .collectList()
            .flatMap(extraAttendeeIds -> openPaaSUserDAO.retrieve(owner)
                .map(OpenPaaSUser::id)
                .filter(extraAttendeeIds::contains)
                .flatMap(ownerId -> Mono.<Void>error(new IllegalArgumentException(
                    "'extraAttendees' must not contain the booking link owner: " + ownerId.value()))))
            .then();
    }

    private boolean isSameDomainOrWarn(Username owner, OpenPaaSUser user) {
        boolean sameDomain = sameDomain(owner, user);
        if (!sameDomain) {
            LOGGER.warn("Skipping extra attendee {}: not in the domain of the booking link owner {}",
                user.id().value(), owner.asString());
        }
        return sameDomain;
    }

    private boolean sameDomain(Username owner, OpenPaaSUser user) {
        Optional<Domain> ownerDomain = owner.getDomainPart();
        return ownerDomain.isPresent() && ownerDomain.equals(user.username().getDomainPart());
    }

    private IllegalArgumentException unknownUser(OpenPaaSId id) {
        return new IllegalArgumentException("'extraAttendees' references a user that does not exist: " + id.value());
    }
}
