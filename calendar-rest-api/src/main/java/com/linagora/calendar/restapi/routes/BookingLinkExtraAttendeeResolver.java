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

import jakarta.inject.Inject;

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

    public Flux<OpenPaaSUser> resolve(List<OpenPaaSId> extraAttendees) {
        return Flux.fromIterable(extraAttendees)
            .concatMap(id -> openPaaSUserDAO.retrieve(id)
                .switchIfEmpty(Mono.error(() -> unknownUser(id))));
    }

    /**
     * Resolves the extra attendees that still exist, skipping the others: a user deleted after the booking link
     * was created must not make the link unbookable.
     */
    public Flux<OpenPaaSUser> resolveExisting(List<OpenPaaSId> extraAttendees) {
        return Flux.fromIterable(extraAttendees)
            .concatMap(id -> openPaaSUserDAO.retrieve(id)
                .switchIfEmpty(Mono.fromRunnable(() ->
                    LOGGER.warn("Skipping extra attendee {}: no such user anymore", id.value()))));
    }

    public Mono<Void> validate(Username owner, List<OpenPaaSId> extraAttendees) {
        if (extraAttendees.isEmpty()) {
            return Mono.empty();
        }
        Mono<OpenPaaSId> ownerId = openPaaSUserDAO.retrieve(owner)
            .map(OpenPaaSUser::id)
            .cache();

        return resolve(extraAttendees)
            .concatMap(extraAttendee -> ownerId
                .filter(id -> id.equals(extraAttendee.id()))
                .flatMap(id -> Mono.<Void>error(new IllegalArgumentException(
                    "'extraAttendees' must not contain the booking link owner: " + id.value()))))
            .then();
    }

    private IllegalArgumentException unknownUser(OpenPaaSId id) {
        return new IllegalArgumentException("'extraAttendees' references a user that does not exist: " + id.value());
    }
}
