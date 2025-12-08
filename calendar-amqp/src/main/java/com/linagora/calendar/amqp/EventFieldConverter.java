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

package com.linagora.calendar.amqp;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.google.common.base.Splitter;
import com.linagora.calendar.amqp.EventProperty.AttendeeProperty;
import com.linagora.calendar.amqp.EventProperty.DateProperty;
import com.linagora.calendar.amqp.EventProperty.DtStampProperty;
import com.linagora.calendar.amqp.EventProperty.EventUidProperty;
import com.linagora.calendar.amqp.EventProperty.OrganizerProperty;
import com.linagora.calendar.amqp.EventProperty.SequenceProperty;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;

public class EventFieldConverter {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new SimpleModule().addDeserializer(EventProperty.class, new EventPropertyDeserializer()));

    public static CalendarEvents from(CalendarEventMessage eventMessage) {
        CalendarURL calendarURL = extractCalendarURL(eventMessage.eventPath);

        return CalendarEvents.of(extractVEventProperties(eventMessage.calendarEvent).stream()
            .map(listEventProperties -> from(listEventProperties)
                .calendarURL(calendarURL).build())
            .collect(Collectors.toSet()));
    }

    public static CalendarURL extractCalendarURL(String eventPath) {
        List<String> paths = Splitter.on('/')
            .omitEmptyStrings()
            .splitToList(eventPath);

        if (paths.size() != 4 || !"calendars".equals(paths.get(0))) {
            throw new CalendarEventDeserializeException("Invalid event path: " + eventPath);
        }
        return new CalendarURL(new OpenPaaSId(paths.get(1)), new OpenPaaSId(paths.get(2)));
    }

    public static EventFields.Builder from(List<EventProperty> eventProperties) {
        EventFields.Builder builder = EventFields.builder();

        for (EventProperty property : eventProperties) {
            switch (property.name) {
                case EventProperty.UID_PROPERTY -> builder.uid(((EventUidProperty) property).getEventUid());
                case EventProperty.DTSTART_PROPERTY -> {
                    DateProperty dateProperty = (DateProperty) property;
                    if (Strings.CS.equals("date", dateProperty.valueType)) {
                        builder.allDay(true);
                    }
                    builder.start(dateProperty.getDate());
                }
                case EventProperty.DTEND_PROPERTY -> builder.end(((DateProperty) property).getDate());
                case EventProperty.DTSTAMP_PROPERTY -> builder.dtStamp(((DtStampProperty) property).getDtStamp());
                case EventProperty.CLASS_PROPERTY -> builder.clazz(property.value);
                case EventProperty.SUMMARY_PROPERTY -> builder.summary(property.value);
                case EventProperty.DESCRIPTION_PROPERTY -> builder.description(property.value);
                case EventProperty.LOCATION_PROPERTY -> builder.location(property.value);
                case EventProperty.ORGANIZER_PROPERTY -> {
                    OrganizerProperty organizerProperty = (OrganizerProperty) property;
                    builder.organizer(new EventFields.Person(organizerProperty.getCn(), organizerProperty.getMailAddress()));
                }
                case EventProperty.ATTENDEE_PROPERTY -> {
                    AttendeeProperty attendeeProperty = (AttendeeProperty) property;
                    EventFields.Person person = new EventFields.Person(attendeeProperty.getCn(), attendeeProperty.getMailAddress());
                    if (Strings.CS.equals("RESOURCE", attendeeProperty.getCutype())) {
                        builder.addResource(person);
                    } else {
                        builder.addAttendee(person);
                    }
                }
                case EventProperty.RECURRENCE_ID_PROPERTY -> builder.isRecurrentMaster(false);
                case EventProperty.RRULE_PROPERTY -> builder.isRecurrentMaster(true);
                case EventProperty.SEQUENCE_PROPERTY -> builder.sequence(((SequenceProperty) property).getSequence());
                case EventProperty.VIDEOCONFERENCE -> builder.videoconferenceUrl(property.value);
                default -> {
                }
            }
        }
        calculateEndTimeFromDuration(eventProperties).ifPresent(builder::end);

        return builder;
    }

    public static List<List<EventProperty>> extractVEventProperties(JsonNode calendarEvent) {
        if (!calendarEvent.isArray() || calendarEvent.size() < 3 || !"vcalendar".equalsIgnoreCase(calendarEvent.get(0).asText())) {
            throw new CalendarEventDeserializeException("Not a valid vcalendar array structure" + calendarEvent.toPrettyString());
        }

        ArrayNode components = (ArrayNode) calendarEvent.get(2);

        return StreamSupport.stream(components.spliterator(), false)
            .filter(component -> component.isArray() && component.size() >= 2
                && "vevent".equalsIgnoreCase(component.get(0).asText()))
            .map(component -> {
                ArrayNode veventProps = (ArrayNode) component.get(1);
                return StreamSupport.stream(veventProps.spliterator(), false)
                    .map(EventFieldConverter::deserializeEventProperty)
                    .collect(Collectors.toList());
            })
            .collect(Collectors.toList());
    }

    private static EventProperty deserializeEventProperty(JsonNode node) {
        try {
            return MAPPER.treeToValue(node, EventProperty.class);
        } catch (JsonProcessingException e) {
            throw new CalendarEventDeserializeException("Cannot deserialize EventProperty" + node.toPrettyString(), e);
        }
    }

    public static class EventPropertyDeserializer extends JsonDeserializer<EventProperty> {

        private static final Map<String, Function<EventProperty, EventProperty>> PROPERTY_HANDLERS = Map.of(
            EventProperty.UID_PROPERTY, EventUidProperty::new,
            EventProperty.ATTENDEE_PROPERTY, AttendeeProperty::new,
            EventProperty.ORGANIZER_PROPERTY, OrganizerProperty::new,
            EventProperty.DTSTART_PROPERTY, DateProperty::new,
            EventProperty.DTEND_PROPERTY, DateProperty::new,
            EventProperty.DTSTAMP_PROPERTY, DtStampProperty::new,
            EventProperty.DURATION_PROPERTY, EventProperty.DurationProperty::new,
            EventProperty.SEQUENCE_PROPERTY, EventProperty.SequenceProperty::new);

        private void validateNode(JsonNode node) {
            if (!node.isArray()) {
                throw new CalendarEventDeserializeException("Expected an array for EventProperty but got " + node.getNodeType() + ": " + node.toPrettyString());
            }
        }

        @Override
        public EventProperty deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.getCodec().readTree(p);

            validateNode(node);

            String name = node.get(0).asText();
            JsonNode param = node.get(1);
            String valueType = node.get(2).asText();
            String value = Optional.ofNullable(node.get(3)).map(JsonNode::asText).orElse(null);

            EventProperty eventProperty = new EventProperty(name, param, valueType, value);

            return PROPERTY_HANDLERS.getOrDefault(name, ep -> ep).apply(eventProperty);
        }
    }

    private static Optional<Instant> calculateEndTimeFromDuration(List<EventProperty> eventProperties) {
        return findPropertyByName(eventProperties, EventProperty.DTSTART_PROPERTY)
            .flatMap(dtStartProperty -> findPropertyByName(eventProperties, EventProperty.DURATION_PROPERTY)
                .map(durationProperty ->
                    ((DateProperty) dtStartProperty).getDate().plus(((EventProperty.DurationProperty) durationProperty).getDuration())));
    }

    private static Optional<EventProperty> findPropertyByName(List<EventProperty> properties, String name) {
        return properties.stream()
            .filter(prop -> name.equals(prop.name))
            .findFirst();
    }
}
