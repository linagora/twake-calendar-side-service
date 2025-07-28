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

package com.linagora.calendar.storage.event;

import static net.fortuna.ical4j.model.Property.ACTION;
import static net.fortuna.ical4j.model.Property.TRIGGER;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Period;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Trigger;

public interface AlarmInstantFactory {
    Optional<Instant> computeNextAlarmInstant(Username attendee, Calendar calendar);

    class Default implements AlarmInstantFactory {

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);

        private final Clock clock;

        public Default(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Optional<Instant> computeNextAlarmInstant(Username attendee, Calendar calendar) {
            Optional<VEvent> upcomingEvent = findUpcomingAcceptedVEvent(calendar, attendee);
            if (upcomingEvent.isEmpty()) {
                return Optional.empty();
            }

            VEvent event = upcomingEvent.get();
            List<VAlarm> alarms = event.getAlarms();
            if (alarms.isEmpty()) {
                return Optional.empty();
            }

            VAlarm firstAlarm = alarms.getFirst();
            if (firstAlarm.getProperty(ACTION).isEmpty()) {
                LOGGER.debug("Event {} has an alarm without ACTION property, skipping", event.getUid());
                return Optional.empty();
            }
            Optional<Trigger> triggerOpt = firstAlarm.getProperty(TRIGGER);
            if (triggerOpt.isEmpty()) {
                LOGGER.debug("Event {} has an alarm without TRIGGER property, skipping", event.getUid());
                return Optional.empty();
            }
            TemporalAmount offset = triggerOpt.get().getDuration();
            return Optional.of(EventParseUtils.getStartTime(event).toInstant().plus(offset));
        }

        private Optional<VEvent> findUpcomingAcceptedVEvent(Calendar calendar, Username attendee) {
            List<VEvent> allEvents = calendar.getComponents(Component.VEVENT);

            if (allEvents.isEmpty()) {
                return Optional.empty();
            }

            if (allEvents.size() == 1 && allEvents.getFirst().getProperty(Property.RRULE).isEmpty()) {
                return findUpcomingFromSingleEvent(allEvents.getFirst(), attendee);
            }

            return findClosestFromRecurringEvents(allEvents, attendee);
        }

        private Optional<VEvent> findUpcomingFromSingleEvent(VEvent event, Username attendee) {
            boolean isAccepted = isAttendeeAccepted(event, attendee);
            boolean isUpcoming = clock.instant().isBefore(EventParseUtils.getStartTime(event).toInstant());

            if (isAccepted && isUpcoming) {
                return Optional.of(event);
            }
            return Optional.empty();
        }

        private Optional<VEvent> findClosestFromRecurringEvents(List<VEvent> events, Username attendee) {
            Optional<VEvent> masterOpt = events.stream()
                .filter(e -> e.getRecurrenceId() == null)
                .findFirst();

            List<VEvent> overrides = events.stream()
                .filter(e -> e.getRecurrenceId() != null)
                .toList();
            return masterOpt.flatMap(master -> findClosestFromRecurrence(master, overrides, attendee));
        }

        private Optional<VEvent> findClosestFromRecurrence(VEvent master, List<VEvent> overrides, Username attendee) {
            RRule<Temporal> rrule = master.getProperty(Property.RRULE)
                .map(property -> (RRule<Temporal>) property)
                .orElseThrow(() -> new IllegalArgumentException("Master event must have an RRULE"));

            List<Instant> excludedInstants = extractExcludedInstants(master);
            Recur recur = rrule.getRecur();
            ZonedDateTime startTime = EventParseUtils.getStartTime(master);

            List<ZonedDateTime> recurrenceDates = recur.getDates(startTime, startTime.plusYears(1));
            List<ZonedDateTime> filteredRecurrenceDates = recurrenceDates.stream()
                .filter(recurrence -> !excludedInstants.contains(recurrence.toInstant()))
                .toList();

            Map<ZonedDateTime, VEvent> overrideMap = overrides.stream()
                .collect(Collectors.toMap(
                    event -> EventParseUtils.temporalToZonedDateTime(event.getRecurrenceId().getDate())
                        .orElseThrow(() -> new IllegalArgumentException("Cannot convert recurrenceId: " + event.getRecurrenceId())),
                    Function.identity()
                ));

            Instant now = clock.instant();
            for (ZonedDateTime recurrenceDate : filteredRecurrenceDates) {
                if (recurrenceDate.toInstant().isAfter(now)) {
                    VEvent event = overrideMap.getOrDefault(recurrenceDate, createInstanceVEvent(master, recurrenceDate));
                    if (isAttendeeAccepted(event, attendee)) {
                        return Optional.of(event);
                    }
                }
            }

            return Optional.empty();
        }

        private boolean isAttendeeAccepted(VEvent vEvent, Username attendee) {
            return EventParseUtils.getAttendees(vEvent).stream()
                .anyMatch(person -> person.email().asString().equalsIgnoreCase(attendee.asString())
                    && person.partStat().map(partStat -> partStat == PartStat.ACCEPTED).orElse(false));
        }

        private List<Instant> extractExcludedInstants(VEvent master) {
            return master.getProperties(Property.EXDATE).stream()
                .map(ExDate.class::cast)
                .flatMap(exDate -> ((List<Temporal>) exDate.getDates()).stream()
                    .map(temporal -> EventParseUtils.temporalToZonedDateTime(temporal)
                        .orElseThrow(() -> new IllegalArgumentException("Cannot convert EXDATE: " + temporal))))
                .map(ZonedDateTime::toInstant)
                .toList();
        }

        public static VEvent createInstanceVEvent(VEvent master, ZonedDateTime recurrenceDate) {
            VEvent instance = master.copy();
            Period recurrencePeriod = new Period(recurrenceDate, recurrenceDate);

            Period actualPeriod = (Period) master.calculateRecurrenceSet(recurrencePeriod).stream()
                .findFirst()
                .orElseThrow();

            removeProperties(instance, Property.RRULE, Property.EXDATE, Property.DTSTART, Property.DTEND, Property.DURATION);

            Function<Temporal, ZonedDateTime> temporalToZonedDateTime = temporal ->
                EventParseUtils.temporalToZonedDateTime(temporal)
                    .orElseThrow(() -> new IllegalArgumentException("Cannot convert temporal: " + temporal));
            addProperties(instance,
                new RecurrenceId<>(recurrenceDate),
                new DtStart<>(temporalToZonedDateTime.apply(actualPeriod.getStart())),
                new DtEnd<>(temporalToZonedDateTime.apply(actualPeriod.getEnd())));

            return instance;
        }

        static void addProperties(VEvent vEvent, Property... properties) {
            List<Property> newProperties = ImmutableList.<Property>builder()
                .addAll(vEvent.getProperties())
                .addAll(Arrays.asList(properties))
                .build();

            vEvent.setPropertyList(new PropertyList(newProperties));
        }

        static void removeProperties(VEvent vEvent, String... propertyNames) {
            Set<String> namesToRemove = Arrays.stream(propertyNames)
                .collect(Collectors.toSet());

            List<Property> filtered = vEvent.getProperties().stream()
                .filter(p -> !namesToRemove.contains(p.getName()))
                .toList();

            vEvent.setPropertyList(new PropertyList(filtered));
        }
    }
}
