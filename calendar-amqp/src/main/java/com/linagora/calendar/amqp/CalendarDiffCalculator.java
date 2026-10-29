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

import java.time.DateTimeException;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.event.EventFields.Person;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public class CalendarDiffCalculator {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarDiffCalculator.class);

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

    public static List<EventDiff> calculate(String recipientEmail, Calendar newCalendar, Calendar oldCalendar) {
        String recipient = StringUtils.lowerCase(recipientEmail);
        if (!EventParseUtils.isRecurringEvent(newCalendar) && !EventParseUtils.isRecurringEvent(oldCalendar)) {
            return calculateSingleEventDiff(recipient, newCalendar, oldCalendar);
        }

        return calculateRecurringEventDiff(recipient, newCalendar, oldCalendar);
    }

    private static List<EventDiff> calculateSingleEventDiff(String recipient, Calendar newCalendar, Calendar oldCalendar) {
        VEvent currentEvent = EventParseUtils.getFirstEvent(newCalendar);
        VEvent previousEvent = EventParseUtils.getFirstEvent(oldCalendar);

        boolean recipientWasAttending = attends(recipient, previousEvent);
        boolean recipientIsAttendingNow = attends(recipient, currentEvent);
        if (!recipientIsAttendingNow && !recipientWasAttending) {
            return ImmutableList.of();
        }

        Optional<List<PropertyChange>> changes = computePropertyChanges(previousEvent, currentEvent);
        boolean organizerAcceptedTransition = organizerAcceptedTransition(previousEvent, currentEvent);
        boolean organizerDeclinedBookingTransition = organizerDeclinedBookingTransition(previousEvent, currentEvent);
        boolean attendeePartStatChanged = attendeePartStatChanged(recipient, previousEvent, currentEvent);
        boolean hasNoRelevantChanges = recipientWasAttending && recipientIsAttendingNow && changes.isEmpty() && !organizerAcceptedTransition && !organizerDeclinedBookingTransition && !attendeePartStatChanged;
        if (hasNoRelevantChanges) {
            return ImmutableList.of();
        }

        return List.of(new EventDiff(currentEvent, !recipientWasAttending, changes));
    }

    private static List<EventDiff> calculateRecurringEventDiff(String recipient, Calendar newCalendar, Calendar oldCalendar) {
        Map<String, VEvent> previousEventsByRecurrenceId = indexByRecurrenceId(oldCalendar);
        VEvent previousMasterEvent = previousEventsByRecurrenceId.get(MASTER_RECURRENCE_ID);
        Map<String, VEvent> currentEventsByRecurrenceId = indexByRecurrenceId(newCalendar);

        return Stream.concat(currentEventsByRecurrenceId.entrySet().stream(),
                revertedOccurrences(previousEventsByRecurrenceId, currentEventsByRecurrenceId))
            .flatMap(entry -> computeDiffForEvent(recipient, previousEventsByRecurrenceId, previousMasterEvent, entry).stream())
            .toList();
    }

    /**
     * Overrides dropped from a series whose RRULE is unchanged: their occurrences go back to the master values.
     */
    private static Stream<Map.Entry<String, VEvent>> revertedOccurrences(Map<String, VEvent> previousEventsByRecurrenceId,
                                                                        Map<String, VEvent> currentEventsByRecurrenceId) {
        VEvent previousMasterEvent = previousEventsByRecurrenceId.get(MASTER_RECURRENCE_ID);
        VEvent currentMasterEvent = currentEventsByRecurrenceId.get(MASTER_RECURRENCE_ID);
        if (previousMasterEvent == null || currentMasterEvent == null || recurrenceRuleChanged(previousMasterEvent, currentMasterEvent)) {
            return Stream.empty();
        }

        return previousEventsByRecurrenceId.entrySet().stream()
            .filter(entry -> !currentEventsByRecurrenceId.containsKey(entry.getKey()))
            .flatMap(entry -> revertedOccurrence(currentMasterEvent, entry.getValue()).stream()
                .map(occurrence -> Map.entry(entry.getKey(), occurrence)));
    }

    /**
     * Empty when the occurrence is now excluded from the series: its cancellation is notified instead.
     */
    private static Optional<VEvent> revertedOccurrence(VEvent currentMasterEvent, VEvent previousOverride) {
        try {
            return Optional.of(occurrenceFromMaster(currentMasterEvent, previousOverride));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private static Optional<EventDiff> computeDiffForEvent(String recipient,
                                                           Map<String, VEvent> previousEventsByRecurrenceId,
                                                           VEvent previousMasterEvent,
                                                           Map.Entry<String, VEvent> currentEventEntry) {
        VEvent previousOccurrence = previousEventsByRecurrenceId.get(currentEventEntry.getKey());
        VEvent currentOccurrence = currentEventEntry.getValue();
        VEvent previousEventForRecipient = Optional.ofNullable(previousOccurrence)
            .orElseGet(() -> toPreviousOccurrenceFromMaster(previousMasterEvent, currentOccurrence)
                .orElse(previousMasterEvent));
        boolean hasPreviousOccurrence = previousOccurrence != null;

        boolean recipientWasAttending = attends(recipient, previousEventForRecipient);
        boolean recipientIsAttendingNow = attends(recipient, currentOccurrence);
        if (!recipientIsAttendingNow && !recipientWasAttending) {
            return Optional.empty();
        }

        Optional<List<PropertyChange>> changes = computePropertyChanges(previousEventForRecipient, currentOccurrence);
        boolean organizerAcceptedTransition = organizerAcceptedTransition(previousEventForRecipient, currentOccurrence);
        boolean recurrenceRuleChanged = recurrenceRuleChanged(previousEventForRecipient, currentOccurrence);
        boolean hasNoRelevantChanges = hasPreviousOccurrence && changes.isEmpty() && !organizerAcceptedTransition && !recurrenceRuleChanged;
        if (hasNoRelevantChanges) {
            return Optional.empty();
        }

        return Optional.of(new EventDiff(currentOccurrence, !recipientWasAttending || recurrenceRuleChanged, changes));
    }

    /**
     * A master whose RRULE appeared, vanished or changed describes another series: the attendee is invited to it
     * again, as the update email does not render recurrence changes.
     */
    static boolean recurrenceRuleChanged(VEvent previousEvent, VEvent currentEvent) {
        return previousEvent != null
            && currentEvent.getRecurrenceId() == null
            && !recurrenceRule(previousEvent).equals(recurrenceRule(currentEvent));
    }

    private static Optional<String> recurrenceRule(VEvent vEvent) {
        return vEvent.getProperty(Property.RRULE)
            .map(Property::getValue);
    }

    private static boolean attendeePartStatChanged(String recipient, VEvent previousEvent, VEvent currentEvent) {
        Function<VEvent, Optional<PartStat>> attendeePartStat = event -> EventParseUtils.getAttendees(event).stream()
            .filter(person -> Strings.CI.equals(person.email().asString(), recipient))
            .findFirst()
            .flatMap(Person::partStat);

        return !attendeePartStat.apply(previousEvent).equals(attendeePartStat.apply(currentEvent));
    }

    private static Optional<VEvent> toPreviousOccurrenceFromMaster(VEvent previousMasterEvent, VEvent currentOccurrence) {
        if (previousMasterEvent == null || currentOccurrence.getRecurrenceId() == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(occurrenceFromMaster(previousMasterEvent, currentOccurrence));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Failed to resolve recurring override", exception);
            return Optional.empty();
        }
    }

    private static VEvent occurrenceFromMaster(VEvent masterEvent, VEvent occurrence) {
        try {
            return EventParseUtils.createInstanceVEvent(masterEvent, occurrence.getRecurrenceId().getDate());
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("Unparsable RECURRENCE-ID", exception);
        }
    }

    private static boolean organizerAcceptedTransition(VEvent previousEvent, VEvent currentEvent) {
        if (previousEvent == null) {
            return false;
        }

        return EventParseUtils.getOrganizer(previousEvent)
            .map(organizer -> {
                boolean wasDeclinedOrNeedAction = organizerPartStat(previousEvent, organizer)
                    .filter(stat -> stat.equals(PartStat.NEEDS_ACTION) || stat.equals(PartStat.DECLINED))
                    .isPresent();

                boolean isNowAccepted = organizerPartStat(currentEvent, organizer)
                    .filter(stat -> stat.equals(PartStat.ACCEPTED) || stat.equals(PartStat.TENTATIVE))
                    .isPresent();

                return wasDeclinedOrNeedAction && isNowAccepted;
            })
            .orElse(false);
    }

    /**
     * The PARTSTAT of the first ATTENDEE entry of the organizer: a duplicated entry must not make one version
     * look both unanswered and accepted.
     */
    private static Optional<PartStat> organizerPartStat(VEvent event, Person organizer) {
        return EventParseUtils.findAttendeePartStat(event, organizer.email());
    }

    /**
     * A publicly created booking the organizer turns down. The refusal is the answer the booker has
     * been waiting for, so it has to be notified even though no other property moved: the organizer
     * PARTSTAT is the only difference between the two versions.
     *
     * <p>Restricted to bookings on purpose. Outside that flow a declining organizer keeps the
     * previous behaviour and notifies nobody, so ordinary meetings are unaffected.
     */
    private static boolean organizerDeclinedBookingTransition(VEvent previousEvent, VEvent currentEvent) {
        if (previousEvent == null || !publiclyCreated(currentEvent)) {
            return false;
        }

        return EventParseUtils.getOrganizer(previousEvent)
            .map(organizer -> {
                // Only from NEEDS-ACTION: DECLINED -> DECLINED is a no-op, not an answer.
                boolean wasNotDeclined = organizerPartStat(previousEvent, organizer)
                    .filter(PartStat.DECLINED::equals)
                    .isEmpty();

                boolean isNowDeclined = organizerPartStat(currentEvent, organizer)
                    .filter(PartStat.DECLINED::equals)
                    .isPresent();

                return wasNotDeclined && isNowDeclined;
            })
            .orElse(false);
    }

    private static boolean publiclyCreated(VEvent event) {
        return EventParseUtils.getPropertyValueIgnoreCase(event, EventEmailConsumer.X_PUBLICLY_CREATED_HEADER)
            .map(BooleanUtils::toBoolean)
            .orElse(false);
    }

    private static Map<String, VEvent> indexByRecurrenceId(Calendar calendar) {
        return calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .filter(Predicate.not(EventParseUtils::hasUnparsableRecurrenceId))
            .collect(Collectors.toMap(
                vevent -> EventParseUtils.getRecurrenceId(vevent).orElse(MASTER_RECURRENCE_ID),
                Function.identity(), (first, second) -> first, LinkedHashMap::new));
    }

    private static boolean attends(String recipient, VEvent vEvent) {
        return vEvent != null && Stream.concat(EventParseUtils.getAttendees(vEvent).stream(), EventParseUtils.getResources(vEvent).stream())
            .anyMatch(person -> Strings.CI.equals(person.email().asString(), recipient));
    }

    private static Optional<List<PropertyChange>> computePropertyChanges(VEvent previous, VEvent current) {
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

    private static Optional<StringPropertyChange> compareStringProperty(VEvent previous, VEvent current, String propertyName) {
        String previousValue = EventParseUtils.getPropertyValueIgnoreCase(previous, propertyName).orElse(StringUtils.EMPTY);
        String currentValue = EventParseUtils.getPropertyValueIgnoreCase(current, propertyName).orElse(StringUtils.EMPTY);
        if (Strings.CS.equals(previousValue, currentValue)) {
            return Optional.empty();
        }
        return Optional.of(new StringPropertyChange(propertyName, previousValue, currentValue));
    }

    private static Stream<DateTimePropertyChange> compareDateTimeProperties(VEvent previous, VEvent current) {
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
