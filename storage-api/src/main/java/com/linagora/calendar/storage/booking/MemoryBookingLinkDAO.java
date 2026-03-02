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

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Username;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryBookingLinkDAO implements BookingLinkDAO {
    private final Table<Username, BookingLinkPublicId, BookingLink> store;
    private final Clock clock;

    @Inject
    public MemoryBookingLinkDAO(Clock clock) {
        this.clock = clock;
        this.store = Tables.synchronizedTable(HashBasedTable.create());
    }

    @Override
    public Mono<BookingLink> insert(Username username, BookingLinkInsertRequest request) {
        return Mono.fromCallable(() -> {
            Instant now = clock.instant();
            BookingLinkPublicId publicId = BookingLinkPublicId.generate();
            BookingLink bookingLink = BookingLink.builder()
                .username(username)
                .publicId(publicId)
                .calendarUrl(request.calendarUrl())
                .duration(request.eventDuration())
                .active(request.active())
                .availabilityRules(request.availabilityRules())
                .createdAt(now)
                .updatedAt(now)
                .build();
            store.put(username, publicId, bookingLink);
            return bookingLink;
        });
    }

    @Override
    public Mono<BookingLink> findByPublicId(Username username, BookingLinkPublicId publicId) {
        return Mono.justOrEmpty(store.get(username, publicId));
    }

    @Override
    public Flux<BookingLink> findByUsername(Username username) {
        return Flux.fromIterable(store.row(username).values());
    }

    @Override
    public Mono<BookingLink> update(Username username, BookingLinkPublicId publicId, BookingLinkPatchRequest request) {
        return findByPublicId(username, publicId)
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .map(existing -> {
                BookingLink.Builder builder = existing.toBuilder();
                request.calendarUrl().ifPresent(builder::calendarUrl);
                request.duration().ifPresent(builder::duration);
                request.active().ifPresent(builder::active);
                request.availabilityRules().ifPresent(rules -> builder.availabilityRules(Optional.of(rules)));

                BookingLink updated = builder
                    .updatedAt(clock.instant())
                    .build();

                store.put(username, publicId, updated);
                return updated;
            });
    }

    @Override
    public Mono<BookingLinkPublicId> resetPublicId(Username username, BookingLinkPublicId publicId) {
        return findByPublicId(username, publicId)
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .map(existing -> {
                BookingLinkPublicId newPublicId = BookingLinkPublicId.generate();
                BookingLink updated = existing.toBuilder()
                    .publicId(newPublicId)
                    .updatedAt(clock.instant())
                    .build();

                store.row(username).remove(publicId);
                store.put(username, newPublicId, updated);
                return newPublicId;
            });
    }

    @Override
    public Mono<Void> delete(Username username, BookingLinkPublicId publicId) {
        return Mono.fromRunnable(() -> store.row(username).remove(publicId));
    }
}
