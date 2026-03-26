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
import java.util.Comparator;
import java.util.Map;
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
    public Mono<BookingLink> findByPublicId(BookingLinkPublicId publicId) {
        return Mono.justOrEmpty(store.column(publicId).values().stream().findFirst());
    }

    @Override
    public Flux<BookingLink> findByUsername(Username username) {
        return Flux.fromIterable(store.row(username).values().stream()
            .sorted(Comparator.comparing(BookingLink::updatedAt).reversed())
            .toList());
    }

    @Override
    public Mono<BookingLink> update(Username username, BookingLinkPublicId publicId, BookingLinkPatchRequest request) {
        return Mono.fromCallable(() -> {
            Instant now = clock.instant();
            BookingLink updated = store.row(username).computeIfPresent(publicId, (ignored, existing) -> {
                BookingLink.Builder builder = existing.toBuilder();
                builder.calendarUrl(request.calendarUrl().getOrElse(existing.calendarUrl()));
                builder.duration(request.duration().getOrElse(existing.duration()));
                builder.active(request.active().getOrElse(existing.active()));
                builder.availabilityRules(request.availabilityRules().notKeptOrElse(existing.availabilityRules()));
                return builder.updatedAt(now).build();
            });

            if (updated == null) {
                throw new BookingLinkNotFoundException(publicId);
            }
            return updated;
        });
    }

    @Override
    public Mono<BookingLinkPublicId> resetPublicId(Username username, BookingLinkPublicId publicId) {
        return Mono.fromCallable(() -> {
            Map<BookingLinkPublicId, BookingLink> userStore = store.row(username);
            BookingLink existing = Optional.ofNullable(userStore.remove(publicId))
                .orElseThrow(() -> new BookingLinkNotFoundException(publicId));
            BookingLinkPublicId newPublicId = BookingLinkPublicId.generate();
            userStore.put(newPublicId, existing.toBuilder()
                .publicId(newPublicId)
                .updatedAt(clock.instant())
                .build());
            return newPublicId;
        });
    }

    @Override
    public Mono<Void> delete(Username username, BookingLinkPublicId publicId) {
        return Mono.fromRunnable(() -> store.row(username).remove(publicId));
    }
}
