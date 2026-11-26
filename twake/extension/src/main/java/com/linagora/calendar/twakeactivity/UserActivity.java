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

import java.time.Instant;

import org.apache.james.core.Domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.twakeactivity.ResourcePath.Collection;

/**
 * Something a user did in an application of the side service, published as a CloudEvent on the activity exchange of the
 * Twake Workplace broker. See docs/configuration/twake-activity.md for the contract.
 */
public record UserActivity(ResourceChange change, Actors actors, Instant time) {
    public enum Application {
        CALENDAR("calendar", Collection.CALENDARS),
        CONTACTS("contacts", Collection.ADDRESS_BOOKS);

        private final String value;
        private final Collection collection;

        Application(String value, Collection collection) {
            this.value = value;
            this.collection = collection;
        }

        public Collection collection() {
            return collection;
        }

        public String source() {
            return SOURCE_PREFIX + value;
        }
    }

    public enum Type {
        EVENT_CREATED(Application.CALENDAR, "eventCreated"),
        CONTACT_CREATED(Application.CONTACTS, "contactCreated");

        private final Application application;
        private final String value;

        Type(Application application, String value) {
            this.application = application;
            this.value = value;
        }

        public Application application() {
            return application;
        }

        // calendar.eventCreated: a consumer binds "#" for everything, "calendar.#" for one application.
        public String routingKey() {
            return application.value + "." + value;
        }

        // com.twake.calendar.eventCreated.v1: distinct from the com.twake.calendar.event.created.v1 of TwakeSpace.
        public String cloudEventType() {
            return TYPE_PREFIX + application.value + "." + value + VERSION_SUFFIX;
        }
    }

    public static final String CONTENT_TYPE = "application/cloudevents+json";
    private static final String SPEC_VERSION = "1.0";
    private static final String SOURCE_PREFIX = "twake://";
    private static final String TYPE_PREFIX = "com.twake.";
    private static final String VERSION_SUFFIX = ".v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public UserActivity {
        Preconditions.checkArgument(actors.domain().isPresent(), "The actor of an activity has a mail domain");
    }

    public String id() {
        return change.id();
    }

    public Type type() {
        return change.type();
    }

    public byte[] serialize() {
        Type type = change.type();
        ResourcePath resource = change.resource();
        Domain domain = actors.domain().orElseThrow();
        ObjectNode event = OBJECT_MAPPER.createObjectNode()
            .put("specversion", SPEC_VERSION)
            .put("id", id())
            .put("source", type.application.source())
            .put("type", type.cloudEventType())
            .put("time", time.toString())
            .put("subject", resource.path())
            .put("twakeactor", actors.actor().asString())
            .put("twakedomain", domain.asString());
        // Absent when the calendar or the address book belongs to no user, such as a domain address book.
        actors.resourceOwner().ifPresent(owner -> event.put("twakeresourceowner", owner.asString()));
        event.putObject("data")
            .put("path", resource.path())
            .put(resource.collection().containerField(), resource.container());
        try {
            return OBJECT_MAPPER.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to serialize " + type.cloudEventType(), e);
        }
    }
}
