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
import com.linagora.calendar.api.Participation.ParticipantAction;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public interface ParticipationTokenSigner {

    /**
     * Signs a token valid for any answer: the answer is given alongside it, see
     * {@link #validateAndExtractParticipation(String, Optional)}.
     */
    Mono<String> signAsJwt(ParticipationGrant grant);

    /**
     * Signs a token bound to the answer of the participation, as the action links did before carrying their answer
     * as a query parameter. Such links, already in mailboxes and without expiry, remain valid.
     */
    Mono<String> signAsJwt(Participation participation);

    /**
     * @param requestedAction the answer given alongside the token. The answer the token is bound to, if any,
     *                        prevails over it.
     */
    Mono<Participation> validateAndExtractParticipation(String jwt, Optional<ParticipantAction> requestedAction);

    default Mono<Participation> validateAndExtractParticipation(String jwt) {
        return validateAndExtractParticipation(jwt, Optional.empty());
    }

    class ParticipationTokenClaimException extends RuntimeException {
        public ParticipationTokenClaimException(String message) {
            super(message);
        }
    }

    class MissingParticipationActionException extends RuntimeException {
        public MissingParticipationActionException(String message) {
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

            Function<ParticipationGrant, Map<String, Object>> GRANT_TO_CLAIM = grant -> {
                ImmutableMap.Builder<String, Object> claims = ImmutableMap.<String, Object>builder()
                    .put(ATTENDEE_EMAIL, grant.attendee().asString())
                    .put(ORGANIZER_EMAIL, grant.organizer().asString())
                    .put(UID, grant.eventUid())
                    .put(CALENDAR_URI, grant.calendarURI());
                grant.recurrenceId().ifPresent(recurrenceId -> claims.put(RECURRENCE_ID, recurrenceId));
                return claims.build();
            };

            Function<Participation, Map<String, Object>> PARTICIPATION_TO_CLAIM = participation ->
                ImmutableMap.<String, Object>builder()
                    .putAll(GRANT_TO_CLAIM.apply(participation.grant()))
                    .put(ACTION, participation.action().name())
                    .build();

            Function<Map<String, Object>, ParticipationClaims> CLAIM_TO_PARTICIPATION_CLAIMS = map -> {
                Preconditions.checkNotNull(map, "Claim map must not be null");
                Function<String, MailAddress> toMailAddress = email -> {
                    try {
                        return new MailAddress(email);
                    } catch (Exception e) {
                        throw new IllegalArgumentException("Invalid email address: " + email, e);
                    }
                };

                List<String> requiredKeys = ImmutableList.of(ORGANIZER_EMAIL, ATTENDEE_EMAIL, UID, CALENDAR_URI);
                List<String> missingKeys = requiredKeys.stream()
                    .filter(key -> !map.containsKey(key))
                    .toList();

                if (!missingKeys.isEmpty()) {
                    throw new IllegalArgumentException("Missing required keys in claim map: " + missingKeys);
                }

                ParticipationGrant grant = new ParticipationGrant(
                    toMailAddress.apply((String) map.get(ORGANIZER_EMAIL)),
                    toMailAddress.apply((String) map.get(ATTENDEE_EMAIL)),
                    (String) map.get(UID),
                    (String) map.get(CALENDAR_URI),
                    Optional.ofNullable((String) map.get(RECURRENCE_ID)));
                Optional<ParticipantAction> boundAction = Optional.ofNullable((String) map.get(ACTION))
                    .map(ParticipantAction::valueOf);
                return new ParticipationClaims(grant, boundAction);
            };
        }

        /**
         * @param boundAction the answer of tokens signed before the action links carried it, empty otherwise
         */
        record ParticipationClaims(ParticipationGrant grant, Optional<ParticipantAction> boundAction) {
            Participation answer(Optional<ParticipantAction> requestedAction) {
                return boundAction.or(() -> requestedAction)
                    .map(grant::answer)
                    .orElseThrow(() -> new MissingParticipationActionException("Missing participation action"));
            }
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
        public Mono<String> signAsJwt(ParticipationGrant grant) {
            return jwtSigner.generate(ParticipationTokenClaim.GRANT_TO_CLAIM.apply(grant));
        }

        @Override
        public Mono<String> signAsJwt(Participation participation) {
            return jwtSigner.generate(ParticipationTokenClaim.PARTICIPATION_TO_CLAIM.apply(participation));
        }

        @Override
        public Mono<Participation> validateAndExtractParticipation(String jwt, Optional<ParticipantAction> requestedAction) {
            Preconditions.checkNotNull(jwt, "Token must not be null");
            Preconditions.checkNotNull(requestedAction, "Requested action must not be null");
            return Mono.fromCallable(() -> jwtVerifier.verify(jwt))
                .map(ParticipationTokenClaim.CLAIM_TO_PARTICIPATION_CLAIMS)
                .onErrorResume(error -> Mono.error(new ParticipationTokenClaimException("Invalid participation token: " + jwt)))
                .map(claims -> claims.answer(requestedAction))
                .subscribeOn(Schedulers.parallel());
        }
    }
}
