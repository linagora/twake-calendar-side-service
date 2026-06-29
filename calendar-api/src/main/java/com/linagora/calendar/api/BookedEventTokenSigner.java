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

package com.linagora.calendar.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public interface BookedEventTokenSigner {

    record BookedEvent(UUID publicBookingLinkId,
                       String calendarId,
                       String ownerId,
                       String eventId) {
        public BookedEvent {
            Preconditions.checkNotNull(publicBookingLinkId, "'publicBookingLinkId' must not be null");
            Preconditions.checkNotNull(calendarId, "'calendarId' must not be null");
            Preconditions.checkNotNull(ownerId, "'ownerId' must not be null");
            Preconditions.checkNotNull(eventId, "'eventId' must not be null");
        }
    }

    Mono<String> signAsJwt(BookedEvent bookedEvent);

    Mono<BookedEvent> validateAndExtract(String jwt);

    class BookedEventTokenClaimException extends RuntimeException {
        public BookedEventTokenClaimException(String message) {
            super(message);
        }
    }

    class Default implements BookedEventTokenSigner {

        interface BookedEventTokenClaim {
            String PUBLIC_BOOKING_LINK_ID = "publicBookingLinkId";
            String CALENDAR_ID = "calendarId";
            String OWNER_ID = "ownerId";
            String EVENT_ID = "eventId";

            Function<BookedEvent, Map<String, Object>> TO_CLAIM = bookedEvent -> Map.of(
                PUBLIC_BOOKING_LINK_ID, bookedEvent.publicBookingLinkId().toString(),
                CALENDAR_ID, bookedEvent.calendarId(),
                OWNER_ID, bookedEvent.ownerId(),
                EVENT_ID, bookedEvent.eventId());

            Function<Map<String, Object>, BookedEvent> FROM_CLAIM = map -> {
                Preconditions.checkNotNull(map, "Claim map must not be null");

                List<String> requiredKeys = ImmutableList.of(PUBLIC_BOOKING_LINK_ID, CALENDAR_ID, OWNER_ID, EVENT_ID);
                List<String> missingKeys = requiredKeys.stream()
                    .filter(key -> !map.containsKey(key))
                    .toList();

                if (!missingKeys.isEmpty()) {
                    throw new IllegalArgumentException("Missing required keys in claim map: " + missingKeys);
                }

                return new BookedEvent(
                    UUID.fromString((String) map.get(PUBLIC_BOOKING_LINK_ID)),
                    (String) map.get(CALENDAR_ID),
                    (String) map.get(OWNER_ID),
                    (String) map.get(EVENT_ID));
            };
        }

        private final JwtSigner jwtSigner;
        private final JwtVerifier jwtVerifier;

        @Inject
        @Singleton
        public Default(JwtSigner jwtSigner, JwtVerifier jwtVerifier) {
            this.jwtSigner = jwtSigner;
            this.jwtVerifier = jwtVerifier;
        }

        @Override
        public Mono<String> signAsJwt(BookedEvent bookedEvent) {
            return jwtSigner.generate(BookedEventTokenClaim.TO_CLAIM.apply(bookedEvent));
        }

        @Override
        public Mono<BookedEvent> validateAndExtract(String jwt) {
            Preconditions.checkNotNull(jwt, "Token must not be null");
            return Mono.fromCallable(() -> jwtVerifier.verify(jwt))
                .map(BookedEventTokenClaim.FROM_CLAIM)
                .onErrorResume(error -> Mono.error(new BookedEventTokenClaimException("Invalid booked event token: " + jwt)))
                .subscribeOn(Schedulers.parallel());
        }
    }
}
