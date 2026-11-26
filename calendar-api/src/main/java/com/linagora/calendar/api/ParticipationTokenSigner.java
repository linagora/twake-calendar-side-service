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
import java.util.Optional;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public interface ParticipationTokenSigner {

    Mono<String> signAsJwt(Participation participation);

    Mono<Participation> validateAndExtractParticipation(String jwt);

    class ParticipationTokenClaimException extends RuntimeException {
        public ParticipationTokenClaimException(String message) {
            super(message);
        }
    }

    class Default implements ParticipationTokenSigner {

        interface ParticipationTokenClaim {
            String ATTENDEE_EMAIL = "attendeeEmail";
            String ORGANIZER_EMAIL = "organizerEmail";
            String UID = "uid";
            String CALENDAR_URI = "calendarURI";
            String ACTION = "action";
            String RECURRENCE_ID = "recurrenceId";

            Function<Participation, Map<String, Object>> PARTICIPATION_TO_CLAIM = participation -> {
                ImmutableMap.Builder<String, Object> claims = ImmutableMap.<String, Object>builder()
                    .put(ATTENDEE_EMAIL, participation.attendee().asString())
                    .put(ORGANIZER_EMAIL, participation.organizer().asString())
                    .put(UID, participation.eventUid())
                    .put(CALENDAR_URI, participation.calendarURI())
                    .put(ACTION, participation.action().name());
                participation.recurrenceId().ifPresent(recurrenceId -> claims.put(RECURRENCE_ID, recurrenceId));
                return claims.build();
            };

            Function<Map<String, Object>, Participation> CLAIM_TO_PARTICIPATION = map -> {
                Preconditions.checkNotNull(map, "Claim map must not be null");
                Function<String, MailAddress> toMailAddress = email -> {
                    try {
                        return new MailAddress(email);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid email address: " + email, e);
                    }
                };

                List<String> requiredKeys = ImmutableList.of(ORGANIZER_EMAIL, ATTENDEE_EMAIL, UID, CALENDAR_URI, ACTION);
                List<String> missingKeys = requiredKeys.stream()
                    .filter(key -> !map.containsKey(key))
                    .toList();

                if (!missingKeys.isEmpty()) {
                    throw new IllegalArgumentException("Missing required keys in claim map: " + missingKeys);
                }

                return new Participation(
                    toMailAddress.apply((String) map.get(ORGANIZER_EMAIL)),
                    toMailAddress.apply((String) map.get(ATTENDEE_EMAIL)),
                    (String) map.get(UID),
                    (String) map.get(CALENDAR_URI),
                    Participation.ParticipantAction.valueOf((String) map.get(ACTION)),
                    Optional.ofNullable((String) map.get(RECURRENCE_ID)));
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
        public Mono<String> signAsJwt(Participation participation) {
            return jwtSigner.generate(ParticipationTokenClaim.PARTICIPATION_TO_CLAIM.apply(participation));
        }

        @Override
        public Mono<Participation> validateAndExtractParticipation(String jwt) {
            Preconditions.checkNotNull(jwt, "Token must not be null");
            return Mono.fromCallable(() -> jwtVerifier.verify(jwt))
                .map(ParticipationTokenClaim.CLAIM_TO_PARTICIPATION)
                .onErrorResume(error -> Mono.error(new ParticipationTokenClaimException("Invalid participation token")))
                .subscribeOn(Schedulers.parallel());
        }
    }
}
