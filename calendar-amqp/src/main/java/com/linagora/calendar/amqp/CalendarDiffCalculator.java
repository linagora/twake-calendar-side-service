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

import static com.linagora.calendar.amqp.CalendarDiffCalculator.DateTimePropertyChange.DateTimeValue;

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
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

public class CalendarDiffCalculator {

    private static final String MASTER_RECURRENCE_ID = "master";
    private static final String PREVIOUS_FIELD = "previous";
    private static final String CURRENT_FIELD = "current";
    private static final int TIMEZONE_TYPE_DEFAULT = 3;
    private static final DateTimeFormatter CHANGE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.000000");
    private static final List<String> STRING_PROPS = List.of(Property.SUMMARY, Property.LOCATION, Property.DESCRIPTION);

    sealed interface PropertyChange permits StringPropertyChange, DateTimePropertyChange {
        String propertyName();

        ObjectNode serialize();
    }

    record StringPropertyChange(String propertyName, String previous, String current) implements PropertyChange {
        @Override
        public ObjectNode serialize() {
            return JsonNodeFactory.instance.objectNode()
                .put(PREVIOUS_FIELD, previous)
                .put(CURRENT_FIELD, current);
        }
    }

    record DateTimePropertyChange(String propertyName,
                                   DateTimeValue previousValue,
                                   DateTimeValue currentValue) implements PropertyChange {
        @Override
        public ObjectNode serialize() {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            Optional.ofNullable(previousValue).map(DateTimeValue::toJson).ifPresent(value -> node.set(PREVIOUS_FIELD, value));
            Optional.ofNullable(currentValue).map(DateTimeValue::toJson).ifPresent(value -> node.set(CURRENT_FIELD, value));
            return node;
        }

        record DateTimeValue(boolean isAllDay, String date, int timezoneType, String timezone) {
            static DateTimeValue from(VEvent event, ZonedDateTime dateTime) {
                return new DateTimeValue(
                    EventParseUtils.isAllDay(event),
                    CHANGE_DATE_FORMATTER.format(dateTime),
                    TIMEZONE_TYPE_DEFAULT,
                    dateTime.getZone().getId());
            }

            ObjectNode toJson() {
                return JsonNodeFactory.instance.objectNode()
                    .put("isAllDay", isAllDay)
                    .put("date", date)
                    .put("timezone_type", timezoneType)
                    .put("timezone", timezone);
            }
        }
    }

    public record EventDiff(VEvent vevent, boolean isNewEvent, Optional<List<PropertyChange>> changes) {
        public Optional<ObjectNode> serializeChanges() {
            return changes
                .map(list -> {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    list.forEach(change -> node.set(change.propertyName().toLowerCase(), change.serialize()));
                    return node;
                })
                .filter(node -> !node.isEmpty());
        }
    }

    public List<EventDiff> calculate(String recipientEmail, Calendar newCalendar, Calendar oldCalendar) {
        String recipient = StringUtils.lowerCase(recipientEmail);
        if (!EventParseUtils.isRecurringEvent(newCalendar) && !EventParseUtils.isRecurringEvent(oldCalendar)) {
            return calculateSingleEventDiff(recipient, newCalendar, oldCalendar);
        }

        return calculateRecurringEventDiff(recipient, newCalendar, oldCalendar);
    }

    private List<EventDiff> calculateSingleEventDiff(String recipient, Calendar newCalendar, Calendar oldCalendar) {
        VEvent currentEvent = EventParseUtils.getFirstEvent(newCalendar);
        VEvent previousEvent = EventParseUtils.getFirstEvent(oldCalendar);

        boolean recipientWasAttending = attends(recipient, previousEvent);
        boolean recipientIsAttendingNow = attends(recipient, currentEvent);
        if (!recipientIsAttendingNow && !recipientWasAttending) {
            return ImmutableList.of();
        }

        Optional<List<PropertyChange>> changes = computePropertyChanges(previousEvent, currentEvent);
        return List.of(new EventDiff(currentEvent, !recipientWasAttending, changes));
    }

    private List<EventDiff> calculateRecurringEventDiff(String recipient, Calendar newCalendar, Calendar oldCalendar) {
        Map<String, VEvent> previousEventsByRecurrenceId = indexByRecurrenceId(oldCalendar);
        VEvent previousMasterEvent = previousEventsByRecurrenceId.get(MASTER_RECURRENCE_ID);
        Map<String, VEvent> currentEventsByRecurrenceId = indexByRecurrenceId(newCalendar);

        return currentEventsByRecurrenceId.entrySet().stream()
            .flatMap(entry -> computeDiffForEvent(recipient, previousEventsByRecurrenceId, previousMasterEvent, entry).stream())
            .toList();
    }

    private Optional<EventDiff> computeDiffForEvent(String recipient,
                                                    Map<String, VEvent> previousEventsByRecurrenceId,
                                                    VEvent previousMasterEvent,
                                                    Map.Entry<String, VEvent> currentEventEntry) {
        VEvent previousOccurrence = previousEventsByRecurrenceId.get(currentEventEntry.getKey());
        VEvent previousEventForRecipient = Optional.ofNullable(previousOccurrence)
            .orElse(previousMasterEvent);
        VEvent currentOccurrence = currentEventEntry.getValue();
        boolean hasPreviousOccurrence = previousOccurrence != null;

        boolean recipientWasAttending = attends(recipient, previousEventForRecipient);
        boolean recipientIsAttendingNow = attends(recipient, currentOccurrence);
        if (!recipientIsAttendingNow && !recipientWasAttending) {
            return Optional.empty();
        }

        Optional<List<PropertyChange>> changes = computePropertyChanges(previousEventForRecipient, currentOccurrence);
        if (hasPreviousOccurrence && changes.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new EventDiff(currentOccurrence, !recipientWasAttending, changes));
    }

    private Map<String, VEvent> indexByRecurrenceId(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .collect(Collectors.toMap(
                vevent -> EventParseUtils.getRecurrenceId(vevent).orElse(MASTER_RECURRENCE_ID),
                Function.identity(), (first, second) -> first, LinkedHashMap::new));
    }

    private boolean attends(String recipient, VEvent vEvent) {
        return vEvent != null && vEvent.getProperties(Property.ATTENDEE).stream()
            .map(Property::getValue)
            .anyMatch(attendee -> Strings.CI.contains(attendee, recipient));
    }

    private Optional<List<PropertyChange>> computePropertyChanges(VEvent previous, VEvent current) {
        if (previous == null) {
            return Optional.empty();
        }

        Stream<PropertyChange> stringChanges = STRING_PROPS.stream()
            .map(property -> compareStringProperty(previous, current, property))
            .flatMap(Optional::stream);

        return Optional.<List<PropertyChange>>of(Stream.concat(stringChanges, compareDateTimeProperties(previous, current))
                .collect(ImmutableList.toImmutableList()))
            .filter(list -> !list.isEmpty());
    }

    private Optional<StringPropertyChange> compareStringProperty(VEvent previous, VEvent current, String propertyName) {
        String previousValue = EventParseUtils.getPropertyValueIgnoreCase(previous, propertyName).orElse(StringUtils.EMPTY);
        String currentValue = EventParseUtils.getPropertyValueIgnoreCase(current, propertyName).orElse(StringUtils.EMPTY);
        if (Strings.CS.equals(previousValue, currentValue)) {
            return Optional.empty();
        }
        return Optional.of(new StringPropertyChange(propertyName, previousValue, currentValue));
    }

    private Stream<DateTimePropertyChange> compareDateTimeProperties(VEvent previous, VEvent current) {
        Stream.Builder<DateTimePropertyChange> changes = Stream.builder();
        ZonedDateTime previousStart = EventParseUtils.getStartTime(previous);
        ZonedDateTime currentStart = EventParseUtils.getStartTime(current);

        if (!Objects.equals(previousStart, currentStart)) {
            changes.add(new DateTimePropertyChange(
                Property.DTSTART,
                DateTimeValue.from(previous, previousStart),
                DateTimeValue.from(current, currentStart)));
        }

        Optional<ZonedDateTime> previousEnd = EventParseUtils.getEndTime(previous);
        Optional<ZonedDateTime> currentEnd = EventParseUtils.getEndTime(current);

        if (!Objects.equals(previousEnd, currentEnd)) {
            changes.add(new DateTimePropertyChange(
                Property.DTEND,
                previousEnd.map(time -> DateTimeValue.from(previous, time)).orElse(null),
                currentEnd.map(time -> DateTimeValue.from(current, time)).orElse(null)));
        }

        return changes.build();
    }

}
