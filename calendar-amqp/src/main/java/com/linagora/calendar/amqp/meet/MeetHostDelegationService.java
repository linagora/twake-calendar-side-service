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

package com.linagora.calendar.amqp.meet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Splitter;
import com.linagora.calendar.amqp.CalendarEventMessage;
import com.linagora.calendar.amqp.EventFieldConverter;
import com.linagora.calendar.amqp.EventProperty;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * On event save, if the ICS carries a Meet URL (X-OPENPAAS-VIDEOCONFERENCE)
 * and one or more delegate emails (X-TWAKE-DELEGATE-HOSTS), grant each
 * delegate administrator access on the underlying Meet room via the
 * external Application API. Failures are logged and swallowed — event
 * processing must never fail because of Meet unavailability.
 */
public class MeetHostDelegationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MeetHostDelegationService.class);
    private static final Splitter DELEGATE_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private final MeetApplicationClient client;

    @Inject
    public MeetHostDelegationService(MeetApplicationClient client) {
        this.client = client;
    }

    public Mono<Void> onEventSaved(CalendarEventMessage eventMessage) {
        return Mono.fromCallable(() -> extractDelegations(eventMessage.calendarEvent()))
            .flatMapMany(Flux::fromIterable)
            .concatMap(delegation -> grantForDelegation(delegation)
                .onErrorResume(error -> {
                    LOGGER.warn("Meet host delegation failed for delegation {} — skipping", delegation, error);
                    return Mono.empty();
                }))
            .then()
            .onErrorResume(error -> {
                LOGGER.warn("Meet host delegation crashed unexpectedly — skipping", error);
                return Mono.empty();
            });
    }

    private Mono<Void> grantForDelegation(Delegation delegation) {
        return client.fetchToken(delegation.organizerEmail())
            .flatMap(token -> client.findRoomIdBySlug(token, delegation.roomSlug())
                .flatMap(roomIdOpt -> {
                    if (roomIdOpt.isEmpty()) {
                        LOGGER.warn("Meet room not found for slug '{}' (organizer {}) — skipping delegates {}",
                            delegation.roomSlug(), delegation.organizerEmail(), delegation.delegateEmails());
                        return Mono.<Void>empty();
                    }
                    String roomId = roomIdOpt.get();
                    return Flux.fromIterable(delegation.delegateEmails())
                        .concatMap(email -> client.grantAccess(token, new MeetApplicationClient.RoomAccessGrant(roomId, email))
                            .onErrorResume(error -> {
                                LOGGER.warn("Grant failed for delegate {} on room {} — skipping this delegate",
                                    email, roomId, error);
                                return Mono.empty();
                            }))
                        .then();
                }));
    }

    /**
     * Walks the raw vcalendar JsonNode and returns one {@link Delegation}
     * per VEVENT that has both an organizer with an email, a
     * X-OPENPAAS-VIDEOCONFERENCE URL, and at least one delegate email
     * in X-TWAKE-DELEGATE-HOSTS. VEVENTs missing any of those are
     * silently skipped.
     */
    static List<Delegation> extractDelegations(JsonNode calendarEvent) {
        List<List<EventProperty>> perEvent = EventFieldConverter.extractVEventProperties(
            calendarEvent,
            EventProperty.ORGANIZER_PROPERTY,
            EventProperty.VIDEOCONFERENCE,
            EventProperty.DELEGATE_HOSTS);

        return perEvent.stream()
            .map(MeetHostDelegationService::toDelegation)
            .flatMap(Optional::stream)
            .toList();
    }

    private static Optional<Delegation> toDelegation(List<EventProperty> properties) {
        return organizerFrom(properties)
            .flatMap(organizerEmail -> valueOf(properties, EventProperty.VIDEOCONFERENCE)
                .flatMap(meetUrl -> valueOf(properties, EventProperty.DELEGATE_HOSTS)
                    .flatMap(delegatesRaw -> buildDelegation(organizerEmail, meetUrl, delegatesRaw))));
    }

    private static Optional<MailAddress> organizerFrom(List<EventProperty> properties) {
        return properties.stream()
            .filter(EventProperty.OrganizerProperty.class::isInstance)
            .map(EventProperty.OrganizerProperty.class::cast)
            .map(EventProperty.OrganizerProperty::getMailAddress)
            .findFirst();
    }

    private static Optional<String> valueOf(List<EventProperty> properties, String name) {
        return properties.stream()
            .filter(property -> name.equals(property.name()))
            .map(EventProperty::value)
            .findFirst();
    }

    private static Optional<Delegation> buildDelegation(MailAddress organizerEmail, String meetUrl, String delegatesRaw) {
        List<String> delegates = DELEGATE_SPLITTER.splitToList(delegatesRaw);
        if (delegates.isEmpty()) {
            return Optional.empty();
        }
        return extractSlug(meetUrl)
            .map(slug -> new Delegation(organizerEmail, slug, delegates));
    }

    static Optional<MeetApplicationClient.RoomSlug> extractSlug(String meetUrl) {
        try {
            URI uri = new URI(meetUrl);
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) {
                return Optional.empty();
            }
            List<String> segments = Arrays.stream(path.split("/"))
                .filter(s -> !s.isBlank())
                .toList();
            if (segments.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new MeetApplicationClient.RoomSlug(segments.get(segments.size() - 1)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /** Parsed hosting delegation intent, per VEVENT. */
    public record Delegation(MailAddress organizerEmail, MeetApplicationClient.RoomSlug roomSlug, List<String> delegateEmails) {
    }
}
