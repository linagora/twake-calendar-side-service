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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.RegExUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.RecurrenceId;
import net.fortuna.ical4j.model.property.Trigger;

public interface AlarmInstantFactory {
    record AlarmInstant(Instant alarmTime,
                        Instant eventStartTime,
                        Optional<RecurrenceId<Temporal>> recurrenceId,
                        List<MailAddress> recipients,
                        AlarmAction action) {
        public AlarmInstant {
            Preconditions.checkArgument(alarmTime != null, "alarmTime must not be null");
            Preconditions.checkArgument(eventStartTime != null, "eventStartTime must not be null");
            Preconditions.checkArgument(action != null, "action must not be null");
        }

        public AlarmInstant withRecipients(List<MailAddress> recipients) {
            return new AlarmInstant(alarmTime, eventStartTime, recurrenceId, recipients, action);
        }
    }

    Optional<AlarmInstant> computeNextAlarmInstant(Calendar calendar, Username username, Optional<Instant> sinceInstant);

    default Optional<AlarmInstant> computeNextAlarmInstant(Calendar calendar, Username username) {
        return computeNextAlarmInstant(calendar, username, Optional.empty());
    }

    class Default implements AlarmInstantFactory {

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);
        // Bounds the CPU spent on a single computation, whatever the recurrence rule
        private static final int MAX_RECURRENCE_COUNT = 81920;
        private static final int MAX_ITERATED_OCCURRENCES = 81920;
        private static final Comparator<AlarmInstant> EARLIEST_FIRST_ALARM_COMPARATOR =
            Comparator.comparing(AlarmInstant::alarmTime);
        private static final Comparator<VEvent> LATEST_OVERRIDE_EVENT_COMPARATOR =
            Comparator.comparing(Default::extractDtStamp, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Default::extractSequence);

        private final Clock clock;

        public Default(Clock clock) {
            this.clock = clock;
        }

        @Override
        public Optional<AlarmInstant> computeNextAlarmInstant(Calendar calendar, Username username, Optional<Instant> sinceInstant) {
            if (EventParseUtils.isCancelled(calendar)) {
                return Optional.empty();
            }

            Instant sinceInstantValue = sinceInstant.orElse(clock.instant());
            MailAddress userMailAddress = Throwing.supplier(username::asMailAddress).get();
            List<VEvent> allEvents = calendar.getComponents(Component.VEVENT);

            boolean isRecurrence = allEvents.stream()
                .anyMatch(e -> e.getProperty(Property.RRULE).isPresent());
            if (isRecurrence) {
                return computeNextRecurringAlarmInstant(allEvents, username, userMailAddress, sinceInstantValue);
            }
            return computeNextAlarmInstant(listUpcomingAcceptedVEvents(allEvents, username).stream(), userMailAddress, sinceInstantValue);
        }

        private Optional<AlarmInstant> computeNextAlarmInstant(Stream<VEvent> events, MailAddress userMailAddress, Instant sinceInstant) {
            return events
                .filter(event -> !EventParseUtils.isCancelled(event))
                .flatMap(event -> computeAlarmInstants(event, userMailAddress).stream())
                .filter(alarmInstant -> alarmInstant.alarmTime().isAfter(sinceInstant))
                .min(EARLIEST_FIRST_ALARM_COMPARATOR);
        }

        private Optional<AlarmInstant> computeNextRecurringAlarmInstant(List<VEvent> events, Username username,
                                                                        MailAddress userMailAddress, Instant sinceInstant) {
            Optional<VEvent> masterOpt = events.stream()
                .filter(e -> e.getRecurrenceId() == null)
                .findFirst();

            List<VEvent> overrideEvents = events.stream()
                .filter(e -> e.getRecurrenceId() != null)
                .toList();
            return masterOpt.flatMap(master -> computeNextRecurringAlarmInstant(master, overrideEvents, username, userMailAddress, sinceInstant));
        }

        private Optional<AlarmInstant> computeNextRecurringAlarmInstant(VEvent master, List<VEvent> overrideEvents, Username username,
                                                                        MailAddress userMailAddress, Instant sinceInstant) {
            UpcomingOccurrences upcomingOccurrences = new UpcomingOccurrences(master, clock.instant());

            Map<Temporal, VEvent> overrideMap = overrideEvents.stream()
                .collect(Collectors.toMap(event -> normalizeTemporal(event.getRecurrenceId().getDate()),
                    Function.identity(),
                    Default::selectLatestOverride));

            Optional<AlarmInstant> nextOverrideAlarm = computeNextAlarmInstant(overrideMap.entrySet().stream()
                    .filter(entry -> upcomingOccurrences.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .filter(event -> hasAccepted(event, username)),
                userMailAddress, sinceInstant);

            if (!hasAccepted(master, username) || EventParseUtils.isCancelled(master)) {
                return nextOverrideAlarm;
            }
            return computeNextOccurrenceAlarmInstant(master, upcomingOccurrences, overrideMap.keySet(), userMailAddress, sinceInstant, nextOverrideAlarm);
        }

        // Occurrences that are not overridden share the alarms of the master: they are expanded lazily, in
        // chronological order, and the expansion stops as soon as no later occurrence can ring earlier.
        private Optional<AlarmInstant> computeNextOccurrenceAlarmInstant(VEvent master, UpcomingOccurrences upcomingOccurrences,
                                                                         Set<Temporal> overriddenDates, MailAddress userMailAddress,
                                                                         Instant sinceInstant, Optional<AlarmInstant> nextOverrideAlarm) {
            List<AlarmTemplate> alarmTemplates = alarmTemplates(master, userMailAddress);
            if (alarmTemplates.isEmpty()) {
                return nextOverrideAlarm;
            }

            Optional<AlarmInstant> nextAlarm = nextOverrideAlarm;
            Iterator<Temporal> recurrenceDates = upcomingOccurrences.startingFrom(earliestRelevantEventStart(alarmTemplates, sinceInstant))
                .filter(Predicate.not(overriddenDates::contains))
                .limit(MAX_ITERATED_OCCURRENCES)
                .iterator();
            while (recurrenceDates.hasNext()) {
                Temporal recurrenceDate = recurrenceDates.next();
                ZonedDateTime eventStart = upcomingOccurrences.startTime(recurrenceDate);
                if (nextAlarm.isPresent() && !earliestAlarmTime(alarmTemplates, eventStart).isBefore(nextAlarm.get().alarmTime())) {
                    break;
                }
                Optional<RecurrenceId<Temporal>> recurrenceId = Optional.of(EventParseUtils.createInstanceRecurrenceId(master, recurrenceDate));
                nextAlarm = Stream.concat(nextAlarm.stream(), alarmTemplates.stream()
                        .map(alarmTemplate -> alarmTemplate.instantiate(eventStart, recurrenceId))
                        .filter(alarmInstant -> alarmInstant.alarmTime().isAfter(sinceInstant)))
                    .min(EARLIEST_FIRST_ALARM_COMPARATOR);
            }
            return nextAlarm;
        }

        // An alarm rings after sinceInstant only if the event starts after sinceInstant minus its trigger. One day
        // of margin absorbs daylight saving time shifts of nominal (P1D) triggers.
        private static Instant earliestRelevantEventStart(List<AlarmTemplate> alarmTemplates, Instant sinceInstant) {
            ZonedDateTime since = sinceInstant.atZone(ZoneOffset.UTC);
            return alarmTemplates.stream()
                .map(alarmTemplate -> since.minus(alarmTemplate.trigger()).minusDays(1).toInstant())
                .min(Comparator.naturalOrder())
                .orElse(sinceInstant);
        }

        private static Instant earliestAlarmTime(List<AlarmTemplate> alarmTemplates, ZonedDateTime eventStart) {
            return alarmTemplates.stream()
                .map(alarmTemplate -> eventStart.plus(alarmTemplate.trigger()).toInstant())
                .min(Comparator.naturalOrder())
                .orElseThrow();
        }

        private List<AlarmInstant> computeAlarmInstants(VEvent event, MailAddress userMailAddress) {
            ZonedDateTime eventStart = EventParseUtils.getStartTime(event);
            Optional<RecurrenceId<Temporal>> recurrenceId = Optional.ofNullable(event.getRecurrenceId());

            return alarmTemplates(event, userMailAddress).stream()
                .map(alarmTemplate -> alarmTemplate.instantiate(eventStart, recurrenceId))
                .toList();
        }

        private List<AlarmTemplate> alarmTemplates(VEvent event, MailAddress userMailAddress) {
            return event.getAlarms().stream()
                .map(vAlarm -> extractTriggerDurationIfValid(vAlarm)
                    .map(triggerDuration -> new AlarmTemplate(triggerDuration,
                        extractAction(vAlarm).orElse(AlarmAction.DISPLAY),
                        extractRecipients(vAlarm))))
                .flatMap(Optional::stream)
                .map(alarmTemplate -> filterRecipientsForUser(event, alarmTemplate, userMailAddress))
                .flatMap(Optional::stream)
                .toList();
        }

        private Optional<AlarmTemplate> filterRecipientsForUser(VEvent event, AlarmTemplate alarmTemplate, MailAddress userMailAddress) {
            if (alarmTemplate.recipients().isEmpty()) {
                return Optional.of(alarmTemplate);
            }

            Set<MailAddress> otherEventAttendeeMailAddresses = EventParseUtils.getAttendees(event).stream()
                .map(EventFields.Person::email)
                .filter(attendee -> !attendee.equals(userMailAddress))
                .collect(Collectors.toSet());

            return Optional.of(alarmTemplate.recipients().stream()
                    .filter(Predicate.not(otherEventAttendeeMailAddresses::contains))
                    .distinct()
                    .toList())
                .filter(Predicate.not(List::isEmpty))
                .map(alarmTemplate::withRecipients);
        }

        private List<MailAddress> extractRecipients(VAlarm vAlarm) {
            return vAlarm.getProperties(ATTENDEE).stream()
                .flatMap(property -> {
                    try {
                        return Optional.ofNullable(((Attendee) property).getCalAddress())
                            .map(Object::toString)
                            // Legacy ICS can contain repeated mailto: prefixes in VALARM recipients.
                            .map(value -> RegExUtils.removeFirst(value, "(?i)^(?:mailto:)+"))
                            .filter(StringUtils::isNotEmpty)
                            .map(Throwing.function(MailAddress::new))
                            .stream();
                    } catch (Exception e) {
                        LOGGER.warn("Invalid email address in VALARM ATTENDEE property: {}. Skipping", property, e);
                        return Optional.<MailAddress>empty().stream();
                    }
                })
                .toList();
        }

        private Optional<TemporalAmount> extractTriggerDurationIfValid(VAlarm alarm) {
            Optional<String> actionOpt = alarm.getProperty(ACTION)
                .map(action -> StringUtils.upperCase(action.getValue(), Locale.US));

            if (actionOpt.isEmpty() || !AlarmAction.SUPPORTED_VALUES.contains(actionOpt.get())) {
                LOGGER.debug("Alarm is missing ACTION or has invalid value, allowed values are: {}. Skipping",
                    AlarmAction.SUPPORTED_VALUES);
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

        private Optional<AlarmAction> extractAction(VAlarm alarm) {
            return alarm.getProperty(ACTION)
                .map(action -> StringUtils.upperCase(action.getValue(), Locale.US))
                .flatMap(AlarmAction::fromString);
        }

        private List<VEvent> listUpcomingAcceptedVEvents(List<VEvent> allEvents, Username username) {
            return allEvents.stream()
                .max(new VEventComparator())
                .map(event -> findUpcomingFromSingleEventAsList(event, username))
                .orElse(List.of());
        }

        private List<VEvent> findUpcomingFromSingleEventAsList(VEvent event, Username username) {
            return findUpcomingFromSingleEvent(event, username)
                .map(List::of)
                .orElse(List.of());
        }

        private Optional<VEvent> findUpcomingFromSingleEvent(VEvent event, Username username) {
            boolean isAcceptedOrOrganizer = hasAccepted(event, username);
            boolean isUpcoming = clock.instant().isBefore(EventParseUtils.getStartTime(event).toInstant());

            if (isAcceptedOrOrganizer && isUpcoming) {
                return Optional.of(event);
            }
            return Optional.empty();
        }

        private boolean hasAccepted(VEvent vEvent, Username username) {
            return EventParseUtils.getAttendees(vEvent).stream()
                .anyMatch(person -> person.email().asString().equalsIgnoreCase(username.asString())
                    && person.partStat().map(partStat -> partStat == PartStat.ACCEPTED).orElse(false));
        }

        private static Temporal normalizeTemporal(Temporal temporal) {
            if (temporal instanceof LocalDate) {
                return temporal;
            }
            return EventParseUtils.temporalToZonedDateTime(temporal)
                .map(ZonedDateTime::toInstant)
                .orElseThrow(() -> new IllegalArgumentException("Cannot convert: " + temporal));
        }

        private static VEvent selectLatestOverride(VEvent first, VEvent second) {
            return LATEST_OVERRIDE_EVENT_COMPARATOR.compare(first, second) >= 0 ? first : second;
        }

        private static Instant extractDtStamp(VEvent event) {
            return event.getProperty(Property.DTSTAMP)
                .flatMap(EventParseUtils::parseTimeAsInstant)
                .orElse(null);
        }

        private static int extractSequence(VEvent event) {
            return event.getSequence() == null ? 0 : event.getSequence().getSequenceNo();
        }

        private record AlarmTemplate(TemporalAmount trigger, AlarmAction action, List<MailAddress> recipients) {
            AlarmTemplate withRecipients(List<MailAddress> recipients) {
                return new AlarmTemplate(trigger, action, recipients);
            }

            AlarmInstant instantiate(ZonedDateTime eventStart, Optional<RecurrenceId<Temporal>> recurrenceId) {
                return new AlarmInstant(eventStart.plus(trigger).toInstant(), eventStart.toInstant(), recurrenceId, recipients, action);
            }
        }

        // Occurrences of a master event starting after now and within the next year, excluding EXDATEs. Recurrence
        // dates are normalized: LocalDate for all-day events, Instant otherwise.
        private static class UpcomingOccurrences {
            private final Recur<Temporal> recur;
            private final Supplier<Set<Temporal>> countedOccurrences;
            private final Temporal seed;
            private final ZoneId zoneId;
            private final List<Temporal> excludedDates;
            private final Instant now;
            private final Temporal periodEnd;

            UpcomingOccurrences(VEvent master, Instant now) {
                RRule<Temporal> rrule = master.getProperty(Property.RRULE)
                    .map(property -> (RRule<Temporal>) property)
                    .orElseThrow(() -> new IllegalArgumentException("Master event must have an RRULE: " + master));
                this.recur = capCount(rrule.getRecur());
                this.zoneId = EventParseUtils.getZoneIdFromStartDate(master).orElse(EventParseUtils.ZONE_ID_DEFAULT);
                this.excludedDates = extractExDates(master);
                this.now = now;
                if (EventParseUtils.isAllDay(master)) {
                    this.seed = master.getDateTimeStart().getDate();
                    this.periodEnd = LocalDate.ofInstant(now, ZoneOffset.UTC).plusYears(1);
                } else {
                    ZonedDateTime seedStart = EventParseUtils.getStartTime(master);
                    this.seed = seedStart;
                    this.periodEnd = now.atZone(seedStart.getZone()).plusYears(1);
                }
                this.countedOccurrences = Suppliers.memoize(() -> expand(seed, periodEnd, -1)
                    .filter(this::isUpcoming)
                    .collect(Collectors.toCollection(LinkedHashSet::new)));
            }

            private static Recur<Temporal> capCount(Recur<Temporal> recur) {
                if (recur.getCount() > MAX_RECURRENCE_COUNT) {
                    return new Recur.Builder<>(recur).count(MAX_RECURRENCE_COUNT).build();
                }
                return recur;
            }

            Stream<Temporal> startingFrom(Instant instant) {
                Instant periodStart = instant.isAfter(now) ? instant : now;
                if (isCounted()) {
                    return countedOccurrences.get().stream()
                        .filter(date -> !startTime(date).toInstant().isBefore(periodStart));
                }
                return expand(toSeedType(periodStart), periodEnd, -1)
                    .filter(this::isUpcoming);
            }

            boolean contains(Temporal recurrenceDate) {
                if (isCounted()) {
                    return countedOccurrences.get().contains(recurrenceDate);
                }
                return isUpcoming(recurrenceDate) && toSeedType(recurrenceDate)
                    .map(date -> expand(date, date, 1).anyMatch(recurrenceDate::equals))
                    .orElse(false);
            }

            // ical4j can not skip the occurrences before the period start of a rule with a COUNT: such a series (at
            // most MAX_RECURRENCE_COUNT occurrences) is expanded once from its start.
            private boolean isCounted() {
                return recur.getCount() > 0;
            }

            ZonedDateTime startTime(Temporal recurrenceDate) {
                if (recurrenceDate instanceof LocalDate localDate) {
                    return localDate.atStartOfDay(ZoneOffset.UTC);
                }
                return ((Instant) recurrenceDate).atZone(zoneId);
            }

            // Without COUNT, ical4j skips the occurrences before the period start: the cost does not depend on how
            // far in the past DTSTART is.
            private Stream<Temporal> expand(Temporal periodStart, Temporal periodEnd, int maxCount) {
                return recur.getDatesAsStream(seed, periodStart, periodEnd, maxCount)
                    .map(Default::normalizeTemporal);
            }

            private boolean isUpcoming(Temporal recurrenceDate) {
                if (excludedDates.contains(recurrenceDate)) {
                    return false;
                }
                if (recurrenceDate instanceof LocalDate localDate) {
                    return localDate.atStartOfDay(ZoneOffset.UTC).toInstant().isAfter(now);
                }
                return ((Instant) recurrenceDate).isAfter(now);
            }

            private Temporal toSeedType(Instant instant) {
                if (seed instanceof LocalDate) {
                    return LocalDate.ofInstant(instant, ZoneOffset.UTC);
                }
                return instant.atZone(((ZonedDateTime) seed).getZone());
            }

            private Optional<Temporal> toSeedType(Temporal recurrenceDate) {
                return switch (recurrenceDate) {
                    case LocalDate localDate when seed instanceof LocalDate -> Optional.of(localDate);
                    case Instant instant when seed instanceof ZonedDateTime -> Optional.of(toSeedType(instant));
                    default -> Optional.empty();
                };
            }

            private static List<Temporal> extractExDates(VEvent master) {
                return master.getProperties(Property.EXDATE).stream()
                    .map(ExDate.class::cast)
                    .flatMap(exDate -> ((List<Temporal>) exDate.getDates()).stream()
                        .map(Default::normalizeTemporal))
                    .toList();
            }
        }
    }
}
