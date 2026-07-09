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

import org.apache.james.core.Username;
import org.apache.james.events.EventBus;

import com.linagora.calendar.storage.BookingLinkStateChangedEvent;
import com.linagora.calendar.storage.UsernameRegistrationKey;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Notifies the booking link owner of any mutation of his booking links.
 * <p>
 * The event is always keyed on the owner rather than on the writer, so that an administrator
 * acting on behalf of a user still notifies that user.
 */
public class EventBusBookingLinkDAO implements BookingLinkDAO {
    private final BookingLinkDAO delegate;
    private final EventBus eventBus;

    public EventBusBookingLinkDAO(BookingLinkDAO delegate, EventBus eventBus) {
        this.delegate = delegate;
        this.eventBus = eventBus;
    }

    @Override
    public Mono<BookingLink> insert(Username username, BookingLinkInsertRequest request) {
        return delegate.insert(username, request)
            .flatMap(bookingLink -> notifyOwner(username).thenReturn(bookingLink));
    }

    @Override
    public Mono<BookingLink> findByPublicId(BookingLinkPublicId publicId) {
        return delegate.findByPublicId(publicId);
    }

    @Override
    public Mono<BookingLink> findByPublicId(Username username, BookingLinkPublicId publicId) {
        return delegate.findByPublicId(username, publicId);
    }

    @Override
    public Flux<BookingLink> findByUsername(Username username) {
        return delegate.findByUsername(username);
    }

    @Override
    public Mono<BookingLink> update(Username username, BookingLinkPublicId publicId, BookingLinkPatchRequest request) {
        return delegate.update(username, publicId, request)
            .flatMap(bookingLink -> notifyOwner(username).thenReturn(bookingLink));
    }

    @Override
    public Mono<BookingLinkPublicId> resetPublicId(Username username, BookingLinkPublicId publicId) {
        return delegate.resetPublicId(username, publicId)
            .flatMap(newPublicId -> notifyOwner(username).thenReturn(newPublicId));
    }

    @Override
    public Mono<Void> delete(Username username, BookingLinkPublicId publicId) {
        return delegate.delete(username, publicId)
            .then(notifyOwner(username));
    }

    private Mono<Void> notifyOwner(Username username) {
        return Mono.from(eventBus.dispatch(BookingLinkStateChangedEvent.of(username),
            new UsernameRegistrationKey(username)));
    }
}
