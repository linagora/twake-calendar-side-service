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

package com.linagora.calendar.twakeactivity;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.twakeactivity.ResourcePath.Collection;
import com.linagora.calendar.twakeactivity.UserActivity.Type;

import reactor.core.publisher.Mono;

/**
 * Tells the activity of a sabre message: the creation of an event in a calendar, or of a contact in an address book.
 * The actor is the user sabre names in {@code connectedUser}; a write made with a technical token names none, and the
 * owner of the calendar or of the address book then stands for the actor. The resource owner is the user owning the
 * calendar home or the address book: the actor when they write their own resources, another user for a delegation.
 */
public class UserActivities {
    public static final String EVENT_CREATED_EXCHANGE = "calendar:event:created";
    public static final String CONTACT_CREATED_EXCHANGE = "sabre:contact:created";
    public static final List<String> EXCHANGES = List.of(EVENT_CREATED_EXCHANGE, CONTACT_CREATED_EXCHANGE);

    private static final Logger LOGGER = LoggerFactory.getLogger(UserActivities.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String USER_PRINCIPAL_PREFIX = "principals/users/";

    // The principals sabre gives: connectedUser for the author, ownerId for the owner of the resource's home.
    private record SabreChange(ResourceChange change, Optional<String> connectedUser, Optional<String> ownerId) {
    }

    private final OpenPaaSUserDAO userDAO;
    private final Clock clock;

    @Inject
    public UserActivities(OpenPaaSUserDAO userDAO, Clock clock) {
        this.userDAO = userDAO;
        this.clock = clock;
    }

    public Mono<UserActivity> handle(String exchange, byte[] body) {
        return Mono.defer(() -> {
            JsonNode message = readTree(body);
            Optional<SabreChange> change = switch (exchange) {
                case EVENT_CREATED_EXCHANGE -> eventCreated(message);
                case CONTACT_CREATED_EXCHANGE -> contactCreated(message);
                default -> Optional.empty();
            };
            return Mono.justOrEmpty(change).flatMap(this::activity);
        });
    }

    // An import tells no activity: importing a calendar would publish one activity per event.
    private static Optional<SabreChange> eventCreated(JsonNode message) {
        if (message.path("import").asBoolean()) {
            return Optional.empty();
        }
        ResourcePath resource = ResourcePath.parse(message.path("eventPath").asText(), Collection.CALENDARS);
        return Optional.of(new SabreChange(new ResourceChange(Type.EVENT_CREATED, resource, message.path("etag").asText("")),
            text(message.get("connectedUser")), Optional.of(resource.homeId())));
    }

    // Sabre also sends a copy for each address book subscribed to or delegated from the one holding the contact:
    // only the original tells the activity.
    private static Optional<SabreChange> contactCreated(JsonNode message) {
        if (text(message.get("sourcePath")).isPresent()) {
            return Optional.empty();
        }
        ResourcePath resource = ResourcePath.parse(message.path("path").asText(), Collection.ADDRESS_BOOKS);
        return Optional.of(new SabreChange(new ResourceChange(Type.CONTACT_CREATED, resource, message.path("etag").asText("")),
            text(message.get("connectedUser")), text(message.get("owner")).flatMap(UserActivities::userId)));
    }

    private Mono<UserActivity> activity(SabreChange sabreChange) {
        ResourceChange change = sabreChange.change();
        return actors(sabreChange)
            .filter(actors -> actors.domain().isPresent())
            .map(actors -> new UserActivity(change, actors, clock.instant()))
            .switchIfEmpty(Mono.fromRunnable(() -> LOGGER.info("Ignoring the {} of {}: neither its author nor its owner is a known user",
                change.type().routingKey(), change.resource().path())));
    }

    private Mono<Actors> actors(SabreChange change) {
        return Mono.justOrEmpty(change.ownerId()).flatMap(this::user)
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty())
            .flatMap(owner -> Mono.justOrEmpty(change.connectedUser().flatMap(UserActivities::userId))
                .flatMap(this::user)
                .switchIfEmpty(Mono.justOrEmpty(owner))
                .map(actor -> new Actors(actor, owner)));
    }

    // A calendar home or an address book home may be a domain or a resource rather than a user.
    private Mono<Username> user(String id) {
        return Mono.defer(() -> userDAO.retrieve(new OpenPaaSId(id)))
            .map(user -> user.username())
            .onErrorResume(IllegalArgumentException.class, e -> Mono.empty());
    }

    private static Optional<String> userId(String principal) {
        return Optional.of(principal)
            .filter(value -> value.startsWith(USER_PRINCIPAL_PREFIX))
            .map(value -> StringUtils.removeStart(value, USER_PRINCIPAL_PREFIX))
            .filter(StringUtils::isNotBlank);
    }

    private static Optional<String> text(JsonNode field) {
        return Optional.ofNullable(field)
            .filter(JsonNode::isTextual)
            .map(JsonNode::asText)
            .filter(StringUtils::isNotBlank);
    }

    private static JsonNode readTree(byte[] body) {
        try {
            JsonNode message = OBJECT_MAPPER.readTree(body);
            if (message == null || !message.isObject()) {
                throw new IllegalArgumentException("A sabre message is a JSON object");
            }
            return message;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read a sabre message", e);
        }
    }
}
