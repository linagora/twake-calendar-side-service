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

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

public class CalendarDiffCalculator {

    private static final String PREVIOUS_FIELD = "previous";
    private static final String CURRENT_FIELD = "current";
    private static final String MASTER_RECURRENCE_ID = "master";

    interface ChangeValue {
        JsonNode serialize();
    }

    sealed interface PropertyChange permits StringPropertyChange, DateTimePropertyChange {
        String propertyName();

        ChangeValue previousValue();

        ChangeValue currentValue();

        default ObjectNode serialize() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            Optional.ofNullable(previousValue())
                .map(ChangeValue::serialize)
                .ifPresent(value -> node.set(PREVIOUS_FIELD, value));
            Optional.ofNullable(currentValue())
                .map(ChangeValue::serialize)
                .ifPresent(value -> node.set(CURRENT_FIELD, value));
            return node;
        }
    }

    record StringPropertyChange(String propertyName, StringValue previousValue, StringValue currentValue) implements PropertyChange {
        record StringValue(String value) implements ChangeValue {
            @Override
            public JsonNode serialize() {
                return JsonNodeFactory.instance.textNode(value);
            }
        }

        static StringPropertyChange from(String propertyName, String previousValue, String currentValue) {
            return new StringPropertyChange(propertyName, new StringValue(previousValue),  new StringValue(currentValue));
        }
    }

    record DateTimePropertyChange(String propertyName, DateTimeValue previousValue, DateTimeValue currentValue) implements PropertyChange {
        private static final String IS_ALL_DAY_FIELD = "isAllDay";
        private static final String DATE_FIELD = "date";
        private static final String TIMEZONE_TYPE_FIELD = "timezone_type";
        private static final String TIMEZONE_FIELD = "timezone";

        record DateTimeValue(boolean isAllDay, String date, int timezoneType, String timezone) implements ChangeValue {
            @Override
            public JsonNode serialize() {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put(IS_ALL_DAY_FIELD, isAllDay);
                node.put(DATE_FIELD, date);
                node.put(TIMEZONE_TYPE_FIELD, timezoneType);
                node.put(TIMEZONE_FIELD, timezone);
                return node;
            }
        }
    }

    public record EventDiff(VEvent vevent, boolean isNewEvent, Optional<List<PropertyChange>> changes) {
        public Optional<ObjectNode> serializeChanges() {
            return changes
                .map(EventDiff::toChangesNode)
                .filter(changesNode -> !changesNode.isEmpty());
        }

        private static ObjectNode toChangesNode(List<PropertyChange> changes) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            changes.forEach(change -> node.set(change.propertyName().toLowerCase(), change.serialize()));
            return node;
        }
    }

    private static final DateTimeFormatter CHANGE_DATE_FORMATTER =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.000000");
    private static final List<String> STRING_PROPS =
        List.of(Property.SUMMARY, Property.LOCATION, Property.DESCRIPTION);
    private static final List<String> DATE_PROPS =
        List.of(Property.DTSTART, Property.DTEND);

    public List<EventDiff> calculate(String recipientEmail, Calendar newCalendar, Calendar oldCalendar) {
        String recipient = StringUtils.lowerCase(recipientEmail);
        Map<String, VEvent> oldEventsByRecurrenceId = indexByRecurrenceId(oldCalendar);

        return indexByRecurrenceId(newCalendar).entrySet().stream()
            .map(entry -> toDiff(recipient, oldEventsByRecurrenceId, entry.getKey(), entry.getValue()))
            .flatMap(Optional::stream)
            .toList();
    }

    private Optional<EventDiff> toDiff(String recipient, Map<String, VEvent> oldEventsByRecurrenceId, String recurrenceId,
                                       VEvent currentOccurrence) {
        VEvent previousOccurrence = oldEventsByRecurrenceId.get(recurrenceId);
        boolean isNewOccurrence = previousOccurrence == null;

        if (!isNewOccurrence && !hasInstanceChanged(previousOccurrence, currentOccurrence)) {
            return Optional.empty();
        }

        VEvent previousForRecipient = Optional.ofNullable(previousOccurrence)
            .orElseGet(() -> oldEventsByRecurrenceId.get(MASTER_RECURRENCE_ID));
        boolean wasAttending = isAttending(recipient, previousForRecipient);
        boolean isAttendingNow = isAttending(recipient, currentOccurrence);

        if (!isAttendingNow && !wasAttending) {
            return Optional.empty();
        }

        Optional<List<PropertyChange>> propertyChanges = computePropertyChanges(previousForRecipient, currentOccurrence);

        return Optional.of(new EventDiff(currentOccurrence, !wasAttending, propertyChanges));
    }

    private Map<String, VEvent> indexByRecurrenceId(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .collect(Collectors.toMap(this::recurrenceIdOrMaster, Function.identity(),
                (a, b) -> a, LinkedHashMap::new));
    }

    private String recurrenceIdOrMaster(VEvent vEvent) {
        return EventParseUtils.getRecurrenceId(vEvent)
            .orElse(MASTER_RECURRENCE_ID);
    }

    private boolean hasInstanceChanged(VEvent old, VEvent current) {
        return hasTrackedPropertyChanges(old, current)
            || hasDateTimeChanges(old, current);
    }

    private boolean hasTrackedPropertyChanges(VEvent old, VEvent current) {
        return STRING_PROPS.stream().anyMatch(property -> !stringPropertyValue(old, property).equals(stringPropertyValue(current, property)))
            || !sortedAttendees(old).equals(sortedAttendees(current));
    }

    private boolean hasDateTimeChanges(VEvent old, VEvent current) {
        return DATE_PROPS.stream()
            .map(property -> compareDateProperty(old, current, property))
            .flatMap(Optional::stream)
            .findAny()
            .isPresent();
    }

    private boolean isAttending(String recipient, VEvent vEvent) {
        if (vEvent == null) {
            return false;
        }
        return vEvent.getProperties(Property.ATTENDEE).stream()
            .map(Property::getValue)
            .anyMatch(attendee -> StringUtils.containsIgnoreCase(attendee, recipient));
    }

    private Optional<List<PropertyChange>> computePropertyChanges(VEvent previous, VEvent current) {
        if (previous == null) {
            return Optional.empty();
        }
        List<PropertyChange> propertyChanges = Stream.concat(
                STRING_PROPS.stream().map(property -> compareStringProperty(previous, current, property)),
                DATE_PROPS.stream().map(property -> compareDateProperty(previous, current, property)))
            .flatMap(Optional::stream)
            .toList();

        if (propertyChanges.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(propertyChanges);
    }

    private Optional<PropertyChange> compareStringProperty(VEvent previous, VEvent current, String propertyName) {
        String previousValue = stringPropertyValue(previous, propertyName);
        String currentValue = stringPropertyValue(current, propertyName);
        if (previousValue.equals(currentValue)) {
            return Optional.empty();
        }
        return Optional.of(StringPropertyChange.from(propertyName, previousValue, currentValue));
    }

    private Optional<PropertyChange> compareDateProperty(VEvent previous, VEvent current, String propertyName) {
        if (Property.DTEND.equals(propertyName)) {
            return compareEndTime(previous, current);
        }

        Optional<Property> previousProperty = previous.getProperty(propertyName);
        Optional<Property> currentProperty = current.getProperty(propertyName);
        if (samePropertyValue(previousProperty, currentProperty)) {
            return Optional.empty();
        }

        return Optional.of(new DateTimePropertyChange(
            propertyName,
            toDateTimeValue(previousProperty),
            toDateTimeValue(currentProperty)));
    }

    private Optional<PropertyChange> compareEndTime(VEvent previous, VEvent current) {
        Optional<Property> previousDtend = previous.getProperty(Property.DTEND);
        Optional<Property> currentDtend = current.getProperty(Property.DTEND);
        if (samePropertyValue(previousDtend, currentDtend) && previousDtend.isPresent()) {
            return Optional.empty();
        }

        Optional<ZonedDateTime> previousEnd = EventParseUtils.getEndTime(previous);
        Optional<ZonedDateTime> currentEnd = EventParseUtils.getEndTime(current);
        if (Objects.equals(previousEnd, currentEnd)) {
            return Optional.empty();
        }

        return Optional.of(new DateTimePropertyChange(
            Property.DTEND,
            toEffectiveEndDateTime(previousDtend, previousEnd, previous),
            toEffectiveEndDateTime(currentDtend, currentEnd, current)));
    }

    private boolean samePropertyValue(Optional<Property> previousProperty, Optional<Property> currentProperty) {
        return Objects.equals(
            previousProperty.map(Property::getValue).orElse(""),
            currentProperty.map(Property::getValue).orElse(""));
    }

    private DateTimePropertyChange.DateTimeValue toDateTimeValue(Optional<Property> property) {
        return property.map(this::toDateTimeValue).orElse(null);
    }

    private DateTimePropertyChange.DateTimeValue toEffectiveEndDateTime(Optional<Property> dtend,
                                                                         Optional<ZonedDateTime> effectiveEnd,
                                                                         VEvent event) {
        return dtend.map(this::toDateTimeValue)
            .or(() -> effectiveEnd.map(zdt -> zdtToDateTimeValue(event, zdt)))
            .orElse(null);
    }

    private DateTimePropertyChange.DateTimeValue toDateTimeValue(Property prop) {
        ZonedDateTime zdt = EventParseUtils.parseTime(prop).orElseGet(() -> ZonedDateTime.now(ZoneOffset.UTC));
        return new DateTimePropertyChange.DateTimeValue(
            EventParseUtils.isDateType(prop),
            CHANGE_DATE_FORMATTER.format(zdt),
            3,
            prop.getParameter(Parameter.TZID).map(Parameter::getValue).orElseGet(() -> zdt.getZone().getId()));
    }

    /** Builds a {@link DateTimePropertyChange.DateTimeValue} from a pre-computed {@link ZonedDateTime} (DURATION fallback). */
    private DateTimePropertyChange.DateTimeValue zdtToDateTimeValue(VEvent event, ZonedDateTime zdt) {
        return new DateTimePropertyChange.DateTimeValue(
            EventParseUtils.isAllDay(event),
            CHANGE_DATE_FORMATTER.format(zdt),
            3,
            zdt.getZone().getId());
    }

    private static String stringPropertyValue(VEvent event, String propertyName) {
        return EventParseUtils.getPropertyValueIgnoreCase(event, propertyName)
            .orElse(StringUtils.EMPTY);
    }

    private static List<String> sortedAttendees(VEvent event) {
        return event.getProperties(Property.ATTENDEE).stream()
            .map(p -> p.getValue().toLowerCase())
            .sorted()
            .toList();
    }

}
