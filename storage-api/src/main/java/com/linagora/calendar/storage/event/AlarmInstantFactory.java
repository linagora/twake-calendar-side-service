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
import static net.fortuna.ical4j.model.Property.ATTENDEE;
import static net.fortuna.ical4j.model.Property.TRIGGER;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.chrono.ChronoZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
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
    record AlarmInstant(Instant alarmTime,
                        Instant eventStartTime,
                        Optional<RecurrenceId<Temporal>> recurrenceId,
                        List<MailAddress> recipients) {
        public AlarmInstant {
            Preconditions.checkArgument(alarmTime != null, "alarmTime must not be null");
            Preconditions.checkArgument(eventStartTime != null, "eventStartTime must not be null");
        }
    }

    Optional<AlarmInstant> computeNextAlarmInstant(Calendar calendar, Username username);

    class Default implements AlarmInstantFactory {

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);
        private static final Comparator<AlarmInstant> EARLIEST_FIRST_ALARM_COMPARATOR =
            Comparator.comparing(AlarmInstant::alarmTime);
        private static final Comparator<VEvent> EARLIEST_FIRST_EVENT_COMPARATOR =
            Comparator.comparing(e -> EventParseUtils.getStartTime(e).toInstant());
        private static final Set<String> VALID_ALARM_ACTIONS = Set.of("EMAIL");

        private final Clock clock;

        public Default(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Optional<AlarmInstant> computeNextAlarmInstant(Calendar calendar, Username username) {
            Instant now = clock.instant();
            return listUpcomingAcceptedVEvents(calendar, username).stream()
                .flatMap(event1 -> computeAlarmInstants(event1).stream())
                .filter(alarmInstant -> alarmInstant.alarmTime().isAfter(now))
                .min(EARLIEST_FIRST_ALARM_COMPARATOR);
        }

        private List<AlarmInstant> computeAlarmInstants(VEvent event) {
            ZonedDateTime eventStart = EventParseUtils.getStartTime(event);
            RecurrenceId<Temporal> recurrenceId = event.getRecurrenceId();

            return event.getAlarms().stream()
                .map(vAlarm -> extractTriggerDurationIfValid(vAlarm)
                    .map(triggerDuration -> Pair.of(triggerDuration, vAlarm)))
                .flatMap(Optional::stream)
                .map(pair -> {
                    ZonedDateTime alarmTime = eventStart.plus(pair.getLeft());
                    return new AlarmInstant(alarmTime.toInstant(),
                        eventStart.toInstant(),
                        Optional.ofNullable(recurrenceId),
                        extractRecipients(pair.getRight()));
                })
                .toList();
        }

        private List<MailAddress> extractRecipients(VAlarm vAlarm) {
            return vAlarm.getProperties(ATTENDEE).stream()
                .map(property -> Strings.CI.removeStart(property.getValue(), "mailto:"))
                .filter(StringUtils::isNotBlank)
                .map(mailAddressValue -> {
                    try {
                        return new MailAddress(mailAddressValue);
                    } catch (AddressException e) {
                        throw new IllegalArgumentException("Invalid email address in ATTENDEE property: " + mailAddressValue, e);
                    }
                })
                .toList();
        }

        private Optional<TemporalAmount> extractTriggerDurationIfValid(VAlarm alarm) {
            Optional<String> actionOpt = alarm.getProperty(ACTION)
                .map(action -> StringUtils.upperCase(action.getValue(), Locale.US));

            if (actionOpt.isEmpty() || !VALID_ALARM_ACTIONS.contains(actionOpt.get())) {
                LOGGER.debug("Alarm is missing ACTION or has invalid value, allowed values are: {}. Skipping",
                    VALID_ALARM_ACTIONS);
                return Optional.empty();
            }

            Optional<Trigger> triggerOpt = alarm.getProperty(TRIGGER);
            if (triggerOpt.isEmpty()) {
                LOGGER.debug("Alarm is missing TRIGGER, skipping");
                return Optional.empty();
            }

            Trigger trigger = triggerOpt.get();
            Optional<TemporalAmount> duration = Optional.ofNullable(trigger.getDuration());
            if (duration.isEmpty()) {
                LOGGER.debug("Alarm is missing TRIGGER, skipping");
            }
            return duration;
        }

        private List<VEvent> listUpcomingAcceptedVEvents(Calendar calendar, Username username) {
            List<VEvent> allEvents = calendar.getComponents(Component.VEVENT);

            if (allEvents.isEmpty()) {
                return List.of();
            }

            if (allEvents.size() == 1 && allEvents.getFirst().getProperty(Property.RRULE).isEmpty()) {
                return findUpcomingFromSingleEvent(allEvents.getFirst(), username)
                    .map(List::of)
                    .orElseGet(List::of);
            }

            return listUpcomingAcceptedRecurringEvents(allEvents, username);
        }

        private Optional<VEvent> findUpcomingFromSingleEvent(VEvent event, Username username) {
            boolean isAcceptedOrOrganizer = hasAcceptedOrIsOrganizer(event, username);
            boolean isUpcoming = clock.instant().isBefore(EventParseUtils.getStartTime(event).toInstant());

            if (isAcceptedOrOrganizer && isUpcoming) {
                return Optional.of(event);
            }
            return Optional.empty();
        }

        private List<VEvent> listUpcomingAcceptedRecurringEvents(List<VEvent> events, Username username) {
            Optional<VEvent> masterOpt = events.stream()
                .filter(e -> e.getRecurrenceId() == null)
                .findFirst();

            List<VEvent> overrides = events.stream()
                .filter(e -> e.getRecurrenceId() != null)
                .toList();
            return masterOpt.map(master -> listUpcomingAcceptedRecurrenceInstances(master, overrides, username))
                .orElseGet(List::of);
        }

        private List<VEvent> listUpcomingAcceptedRecurrenceInstances(VEvent master, List<VEvent> overrides, Username username) {
            RRule<Temporal> rrule = master.getProperty(Property.RRULE)
                .map(property -> (RRule<Temporal>) property)
                .orElseThrow(() -> new IllegalArgumentException("Master event must have an RRULE"));

            List<Instant> excludedInstants = extractExcludedInstants(master);
            Recur recur = rrule.getRecur();
            ZonedDateTime seedStart = EventParseUtils.getStartTime(master);
            ZonedDateTime periodStart = clock.instant().atZone(seedStart.getZone());
            ZonedDateTime periodEnd = periodStart.plusYears(1);

            List<ZonedDateTime> recurrenceDates = recur.getDates(seedStart, periodStart, periodEnd);
            List<Instant> filteredRecurrenceDates = recurrenceDates.stream()
                .map(ChronoZonedDateTime::toInstant)
                .filter(recurrence -> !excludedInstants.contains(recurrence))
                .toList();

            Map<Instant, VEvent> overrideMap = overrides.stream()
                .collect(Collectors.toMap(
                    event -> EventParseUtils.temporalToZonedDateTime(event.getRecurrenceId().getDate())
                        .map(ChronoZonedDateTime::toInstant)
                        .orElseThrow(() -> new IllegalArgumentException("Cannot convert recurrenceId: " + event.getRecurrenceId())),
                    Function.identity()));

            Instant now = clock.instant();
            return filteredRecurrenceDates.stream()
                .filter(recurrenceDate -> recurrenceDate.isAfter(now))
                .map(recurrenceDate -> overrideMap.getOrDefault(
                    recurrenceDate,
                    createInstanceVEvent(master, recurrenceDate.atZone(ZoneOffset.UTC))))
                .filter(event -> hasAcceptedOrIsOrganizer(event, username))
                .sorted(EARLIEST_FIRST_EVENT_COMPARATOR)
                .toList();
        }

        private boolean hasAcceptedOrIsOrganizer(VEvent vEvent, Username username) {
            boolean isOrganizer = Optional.ofNullable(EventParseUtils.getOrganizer(vEvent))
                .map(EventFields.Person::email)
                .map(MailAddress::asString)
                .map(organizerEmail -> organizerEmail.equalsIgnoreCase(username.asString()))
                .orElse(false);

            return isOrganizer
                ||
                EventParseUtils.getAttendees(vEvent).stream()
                    .anyMatch(person -> person.email().asString().equalsIgnoreCase(username.asString())
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

            Function<Temporal, Instant> temporalToZonedDateTime = temporal ->
                EventParseUtils.temporalToZonedDateTime(temporal)
                    .map(ChronoZonedDateTime::toInstant)
                    .orElseThrow(() -> new IllegalArgumentException("Cannot convert temporal: " + temporal));
            addProperties(instance,
                new RecurrenceId<>(recurrenceDate.toInstant()),
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
