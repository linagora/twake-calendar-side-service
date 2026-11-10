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

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.Daylight;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.component.Standard;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.DateListProperty;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.ExDate;
import net.fortuna.ical4j.model.property.RDate;
import net.fortuna.ical4j.model.property.RRule;

public class CalendarEventUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarEventUtils.class);
    @VisibleForTesting
    static final int MAX_EXAMINED_OCCURRENCES = Integer.getInteger("tcalendar.itip.max.examined.occurences", 20_000);
    private static final ZonedDateTime LAST_EXPANDED_DATE = ZonedDateTime.of(9999, 12, 31, 23, 59, 59, 0, ZoneOffset.UTC);
    @VisibleForTesting
    static final long MAX_WALKED_PERIODS = Long.getLong("tcalendar.itip.max.walked.periods", 50_000L);
    /**
     * The candidates the walk may visit, counted as the walked periods times the product of the BY* list sizes. Every
     * real world rule stays far below it: a weekly rule on three days walked from 1900 counts 20,000 of them.
     */
    @VisibleForTesting
    static final long MAX_WALKED_CANDIDATES = Long.getLong("tcalendar.itip.max.walked.candidates", 1_000_000L);
    private static final Duration RELEVANT_LOOK_AHEAD = Duration.ofDays(200L * 366);
    private static final long SECONDS_PER_YEAR = Duration.ofDays(365).getSeconds();
    // Recur#getMaxIncrementCount is not public, and defaults to 1000
    private static final long ICAL4J_PERIODS_WITHOUT_CANDIDATE_BEFORE_GIVING_UP = 1001L;

    public static boolean vEventExpired(VEvent vevent, Clock clock) {
        Optional<ZonedDateTime> startDateTime = vevent.getProperty(Property.DTSTART)
            .flatMap(EventParseUtils::parseTime);
        if (startDateTime.isEmpty()) {
            return false;
        }

        Instant now = clock.instant();
        if (hasNoRecurrence(vevent)) {
            return startDateTime.get().toInstant().isBefore(now);
        }

        return recurrenceExpired(vevent, startDateTime.get(), now);
    }

    /**
     * An unreadable RDATE may designate an upcoming occurrence: expiry is then uncertain and the event is kept.
     * An unreadable EXDATE can only exclude more occurrences, ignoring it errs on the same safe side.
     */
    private static boolean recurrenceExpired(VEvent vevent, ZonedDateTime startDateTime, Instant now) {
        if (hasUnreadableDates(vevent, Property.RDATE)) {
            return false;
        }
        Set<Instant> excludedDates = extractExcludedDates(vevent);
        boolean hasUpcomingFromRRule = hasUpcomingFromRRule(vevent, startDateTime, excludedDates, now);
        boolean hasUpcomingFromRDate = hasUpcomingRDate(vevent, excludedDates, now);
        return !hasUpcomingFromRRule && !hasUpcomingFromRDate;
    }

    private static boolean hasUpcomingFromRRule(VEvent vevent, ZonedDateTime startDateTime, Set<Instant> excludedDates, Instant now) {
        return extractRecur(vevent)
            .map(recur -> isInfinite(recur) || hasUpcomingOccurrenceFromRRule(startDateTime, recur, excludedDates, now))
            .orElseGet(() -> !startDateTime.toInstant().isBefore(now));
    }

    private static boolean hasUpcomingRDate(VEvent vevent, Set<Instant> excludedDates, Instant now) {
        return vevent.getProperties(Property.RDATE).stream()
            .filter(RDate.class::isInstance)
            .map(RDate.class::cast)
            .flatMap(CalendarEventUtils::readableDates)
            .flatMap(date -> toInstant(date).stream())
            .anyMatch(instant -> !excludedDates.contains(instant) && !instant.isBefore(now));
    }

    private static Optional<Recur<Temporal>> extractRecur(VEvent vEvent) {
        return vEvent.getProperty(Property.RRULE)
            .filter(RRule.class::isInstance)
            .map(RRule.class::cast)
            .map(RRule::getRecur)
            .map(recur -> (Recur<Temporal>) recur);
    }

    private static boolean hasNoRecurrence(VEvent vevent) {
        return vevent.getProperty(Property.RRULE).isEmpty() && vevent.getProperty(Property.RDATE).isEmpty();
    }

    private static boolean isInfinite(Recur<Temporal> recur) {
        return recur.getUntil() == null && recur.getCount() <= 0;
    }

    /**
     * DTSTART is the first occurrence (RFC 5545 §3.8.5.3), even when it does not match the rule: ical4j leaves such an
     * unsynchronized DTSTART out of the expansion, hence the explicit check.
     *
     * <p>Then walks the occurrences once, from DTSTART on: ical4j regenerates them from the seed on each call, as COUNT
     * applies from DTSTART. A rule with more than {@link #MAX_EXAMINED_OCCURRENCES} occurrences before {@code now} is
     * deemed not expired, like infinite rules: expiry is then uncertain and the event is kept.
     */
    private static boolean hasUpcomingOccurrenceFromRRule(ZonedDateTime startDateTime,
                                                          Recur<Temporal> recur,
                                                          Set<Instant> excludedDates,
                                                          Instant now) {
        if (isUpcoming(startDateTime.toInstant(), excludedDates, now)) {
            return true;
        }
        if (endsBefore(recur, now)) {
            return false;
        }
        return hasUpcomingExpandedOccurrence(startDateTime, recur, excludedDates, now);
    }

    /**
     * The occurrences are consumed with stream operations, not through {@link Stream#iterator()}: when it
     * rejects an unsynchronized seed, ical4j's spliterator advances without emitting a date, which the iterator
     * adapter mistakes for the end of the occurrences.
     *
     * <p>Advances that emit nothing cost time, and are bounded in two ways, as expiry is uncertain and the event is kept
     * when stopping without a conclusion:
     * <ul>
     *     <li>the walk stops after {@link #MAX_WALKED_PERIODS} periods of the rule,</li>
     *     <li>a rule that would reject too many candidates at once is not walked. ical4j only checks the end of the walk
     *     against its candidates, and BYWEEKNO or an ordinal BYDAY map them back, to the first week of the year or the
     *     first Monday of the month, hence before the seed: {@code FREQ=SECONDLY;BYWEEKNO=1} rejects a year of seconds
     *     whatever the end. RFC 5545 does not allow either on such frequencies.</li>
     *     <li>the work ical4j does in each period grows with the BY* lists. Their repeated values, which change no
     *     occurrence, are dropped before walking, and a rule whose lists still hold too many candidates for the periods to
     *     walk is not walked: see {@link #MAX_WALKED_CANDIDATES}.</li>
     * </ul>
     */
    private static boolean hasUpcomingExpandedOccurrence(ZonedDateTime startDateTime,
                                                         Recur<Temporal> givenRecur,
                                                         Set<Instant> excludedDates,
                                                         Instant now) {
        Recur<Temporal> recur;
        try {
            recur = withDistinctLists(givenRecur);
        } catch (RuntimeException e) {
            LOGGER.info("Cannot rebuild {}: considering the event as not expired", givenRecur, e);
            return true;
        }
        if (rejectsTooManyCandidates(recur) || walksTooManyCandidates(startDateTime, recur)) {
            LOGGER.info("Not walking {}: considering the event as not expired", recur);
            return true;
        }
        try {
            return walkOccurrences(startDateTime, recur, excludedDates, now);
        } catch (DateTimeException | ArithmeticException e) {
            LOGGER.info("Cannot walk {}: considering the event as not expired", recur, e);
            return true;
        }
    }

    private static boolean walkOccurrences(ZonedDateTime startDateTime,
                                           Recur<Temporal> recur,
                                           Set<Instant> excludedDates,
                                           Instant now) {
        ZonedDateTime walkEnd = walkEnd(startDateTime, recur);
        AtomicInteger examined = new AtomicInteger();
        AtomicReference<Instant> lastOccurrence = new AtomicReference<>(startDateTime.toInstant());
        boolean hasUpcoming = recur.getDatesAsStream(startDateTime, startDateTime, walkEnd, -1)
            .limit(MAX_EXAMINED_OCCURRENCES)
            .peek(occurrence -> examined.incrementAndGet())
            .flatMap(occurrence -> toInstant(occurrence).stream())
            .peek(lastOccurrence::set)
            .anyMatch(instant -> isUpcoming(instant, excludedDates, now));
        if (hasUpcoming) {
            return true;
        }
        if (examined.get() >= MAX_EXAMINED_OCCURRENCES) {
            LOGGER.info("More than {} occurrences of {} before {}: considering the event as not expired", MAX_EXAMINED_OCCURRENCES, recur, now);
            return true;
        }
        if (coversTheWholeRule(recur, walkEnd, examined.get(), lastOccurrence.get(), now)) {
            return false;
        }
        LOGGER.info("No conclusion on {} up to {}: considering the event as not expired", recur, walkEnd);
        return true;
    }

    private static boolean rejectsTooManyCandidates(Recur<Temporal> recur) {
        boolean mapsCandidatesBack = !recur.getWeekNoList().isEmpty()
            || recur.getDayList().stream().anyMatch(day -> day.getOffset() != 0);
        return mapsCandidatesBack && SECONDS_PER_YEAR / periodSeconds(recur) > MAX_WALKED_PERIODS;
    }

    @VisibleForTesting
    static Recur<Temporal> withDistinctLists(Recur<Temporal> recur) {
        return new Recur.Builder<>(recur)
            .secondList(distinct(recur.getSecondList()))
            .minuteList(distinct(recur.getMinuteList()))
            .hourList(distinct(recur.getHourList()))
            .dayList(distinct(recur.getDayList()))
            .monthDayList(distinct(recur.getMonthDayList()))
            .yearDayList(distinct(recur.getYearDayList()))
            .weekNoList(distinct(recur.getWeekNoList()))
            .monthList(distinct(recur.getMonthList()))
            .setPosList(distinct(recur.getSetPosList()))
            .build();
    }

    private static <T> List<T> distinct(List<T> values) {
        return values.stream().distinct().toList();
    }

    /**
     * Bounds the candidates the walk may visit: each walked period combines the values of the BY* lists of the rule, so
     * their sizes multiply the cost of a period.
     */
    private static boolean walksTooManyCandidates(ZonedDateTime startDateTime, Recur<Temporal> recur) {
        long candidatesPerPeriod = Stream.of(recur.getSecondList(), recur.getMinuteList(), recur.getHourList(),
                recur.getDayList(), recur.getMonthDayList(), recur.getYearDayList(), recur.getWeekNoList(),
                recur.getMonthList(), recur.getSetPosList())
            .mapToLong(List::size)
            .filter(size -> size > 0)
            .reduce(1L, (a, b) -> Math.min(a * b, MAX_WALKED_CANDIDATES + 1));
        long walkedPeriods = Math.max(1, Duration.between(startDateTime, walkEnd(startDateTime, recur)).getSeconds() / periodSeconds(recur));
        return candidatesPerPeriod > MAX_WALKED_CANDIDATES / walkedPeriods;
    }

    private static ZonedDateTime walkEnd(ZonedDateTime startDateTime, Recur<Temporal> recur) {
        long secondsToLastDate = Duration.between(startDateTime, LAST_EXPANDED_DATE).getSeconds();
        if (secondsToLastDate / periodSeconds(recur) <= MAX_WALKED_PERIODS) {
            return LAST_EXPANDED_DATE;
        }
        return startDateTime.plusSeconds(MAX_WALKED_PERIODS * periodSeconds(recur));
    }

    private static long periodSeconds(Recur<Temporal> recur) {
        // an int interval times at most a year of seconds fits a long
        return unitSeconds(recur) * Math.max(recur.getInterval(), 1);
    }

    /**
     * Months and years are given their shortest length: this only decides when to stop, and stops later for it.
     */
    private static long unitSeconds(Recur<Temporal> recur) {
        return switch (recur.getFrequency()) {
            case SECONDLY -> 1L;
            case MINUTELY -> Duration.ofMinutes(1).getSeconds();
            case HOURLY -> Duration.ofHours(1).getSeconds();
            case DAILY -> Duration.ofDays(1).getSeconds();
            case WEEKLY -> Duration.ofDays(7).getSeconds();
            case MONTHLY -> Duration.ofDays(28).getSeconds();
            case YEARLY -> SECONDS_PER_YEAR;
        };
    }

    /**
     * Whether no occurrence that matters can be left after the walk: it went to the end of the rule, ical4j gave up long
     * before the end of the walk, or the walk went so far ahead that what follows is no reason to notify.
     */
    private static boolean coversTheWholeRule(Recur<Temporal> recur, ZonedDateTime walkEnd, int examined, Instant lastOccurrence, Instant now) {
        Instant end = walkEnd.toInstant();
        return walkEnd.equals(LAST_EXPANDED_DATE)
            || (recur.getCount() > 0 && examined >= recur.getCount())
            || !end.isBefore(now.plus(RELEVANT_LOOK_AHEAD))
            || lastOccurrence.plusSeconds(periodSeconds(recur) * ICAL4J_PERIODS_WITHOUT_CANDIDATE_BEFORE_GIVING_UP).isBefore(end)
            || Optional.ofNullable(recur.getUntil())
                .flatMap(CalendarEventUtils::toInstant)
                .map(until -> !until.isAfter(end))
                .orElse(false);
    }

    private static boolean isUpcoming(Instant instant, Set<Instant> excludedDates, Instant now) {
        return !excludedDates.contains(instant) && !instant.isBefore(now);
    }

    private static boolean endsBefore(Recur<Temporal> recur, Instant now) {
        return Optional.ofNullable(recur.getUntil())
            .flatMap(CalendarEventUtils::toInstant)
            .map(until -> until.isBefore(now))
            .orElse(false);
    }

    private static Set<Instant> extractExcludedDates(VEvent vevent) {
        return vevent.getProperties(Property.EXDATE).stream()
            .map(ExDate.class::cast)
            .flatMap(CalendarEventUtils::readableDates)
            .flatMap(date -> toInstant(date).stream())
            .collect(Collectors.toSet());
    }

    /**
     * ical4j parses date lists lazily: an unreadable value only fails on {@link DateListProperty#getDates()}.
     * Such a property yields no date: callers needing certainty should check {@link #hasUnreadableDates}.
     */
    public static Stream<Temporal> readableDates(DateListProperty<?> property) {
        return parseDates(property)
            .stream()
            .flatMap(List::stream);
    }

    public static boolean hasUnreadableDates(VEvent vevent, String propertyName) {
        return vevent.getProperties(propertyName).stream()
            .filter(DateListProperty.class::isInstance)
            .map(property -> (DateListProperty<?>) property)
            .anyMatch(property -> parseDates(property).isEmpty());
    }

    /**
     * ical4j keeps leniently parsed date values as text and parses them again on rendering: such a VEVENT cannot be
     * serialized. Returns the VEVENT without its unreadable date properties, consistent with {@link #readableDates}.
     *
     * <p>Sub-components are rendered too: VALARMs are kept without their unreadable date properties, and other
     * sub-components, that RFC 5545 does not allow in a VEVENT, are dropped.
     *
     * <p>The supplied VEVENT is left untouched: it may be shared with other calendars.
     */
    public static VEvent withoutUnreadableDates(VEvent vevent) {
        List<Property> readableProperties = readableProperties(vevent.getProperties());
        List<VAlarm> readableAlarms = vevent.getAlarms().stream()
            .map(CalendarEventUtils::withoutUnreadableDates)
            .toList();
        if (readableProperties.size() == vevent.getProperties().size() && hasSameComponents(vevent, readableAlarms)) {
            return vevent;
        }
        return new VEvent(new PropertyList(readableProperties), new ComponentList<>(readableAlarms));
    }

    /**
     * Compares by identity: content equality would compare property values, hence parse unreadable dates again.
     */
    private static boolean hasSameComponents(VEvent vevent, List<VAlarm> readableAlarms) {
        List<? extends Component> components = vevent.getComponents();
        return components.size() == readableAlarms.size()
            && IntStream.range(0, components.size())
                .allMatch(index -> components.get(index) == readableAlarms.get(index));
    }

    private static VAlarm withoutUnreadableDates(VAlarm alarm) {
        List<Property> readableProperties = readableProperties(alarm.getProperties());
        if (readableProperties.size() == alarm.getProperties().size()) {
            return alarm;
        }
        return new VAlarm(new PropertyList(readableProperties));
    }

    private static List<Property> readableProperties(List<Property> properties) {
        return properties.stream()
            .filter(CalendarEventUtils::isRenderable)
            .toList();
    }

    private static boolean isRenderable(Property property) {
        if (!(property instanceof DateProperty<?> || property instanceof DateListProperty<?>)) {
            return true;
        }
        try {
            property.getValue();
            return true;
        } catch (DateTimeException e) {
            // getValue() is the failing parse: the exception message carries the faulty text
            LOGGER.info("Dropping unreadable {} property from the notification payload", property.getName(), e);
            return false;
        }
    }

    /**
     * Same as {@link #withoutUnreadableDates(VEvent)} for a whole calendar, whose properties and non VEVENT components
     * are rendered too. A VTIMEZONE is kept without its unreadable date properties, as VEVENTs reference it. Any other
     * component that cannot be rendered is dropped.
     *
     * <p>The supplied calendar is left untouched, and what can be rendered is kept as is.
     */
    public static Calendar withoutUnreadableDates(Calendar calendar) {
        List<CalendarComponent> nonVEvents = calendar.getComponents().stream()
            .filter(component -> !(component instanceof VEvent))
            .flatMap(CalendarEventUtils::renderableComponent)
            .toList();
        List<VEvent> vEvents = calendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .map(CalendarEventUtils::withoutUnreadableDates)
            .toList();
        return CalendarUtil.withVEvents(new Calendar(renderableProperties(calendar.getProperties()), new ComponentList<>(nonVEvents)), vEvents);
    }

    private static Stream<CalendarComponent> renderableComponent(CalendarComponent component) {
        if (isRenderable(component)) {
            return Stream.of(component);
        }
        if (component instanceof VTimeZone vTimeZone) {
            return Stream.of(new VTimeZone(renderableProperties(vTimeZone.getProperties()),
                new ComponentList<>(vTimeZone.getObservances().stream()
                    .map(CalendarEventUtils::withoutUnreadableDates)
                    .toList())));
        }
        LOGGER.info("Dropping unreadable {} component from the notification payload", component.getName());
        return Stream.empty();
    }

    private static Observance withoutUnreadableDates(Observance observance) {
        PropertyList properties = renderableProperties(observance.getProperties());
        return switch (observance) {
            case Daylight ignored -> new Daylight(properties);
            default -> new Standard(properties);
        };
    }

    private static PropertyList renderableProperties(List<Property> properties) {
        return new PropertyList(properties.stream()
            .filter(CalendarEventUtils::isRenderable)
            .toList());
    }

    private static boolean isRenderable(Component component) {
        try {
            component.toString();
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    /**
     * An RDATE of PERIOD value carries periods instead of dates: each period start is an occurrence.
     */
    private static Optional<List<Temporal>> parseDates(DateListProperty<?> property) {
        try {
            if (property instanceof RDate<?> rDate && isPeriodValued(rDate)) {
                return periodStarts(rDate);
            }
            return Optional.of((List<Temporal>) property.getDates());
        } catch (DateTimeException e) {
            // getValue() would parse again: the exception message carries the faulty text
            LOGGER.info("Unreadable {} property", property.getName(), e);
            return Optional.empty();
        }
    }

    private static boolean isPeriodValued(RDate<?> rDate) {
        return rDate.getParameter(Parameter.VALUE)
            .map(Value.PERIOD::equals)
            .orElse(false);
    }

    private static Optional<List<Temporal>> periodStarts(RDate<?> rDate) {
        return rDate.getPeriods()
            .map(periods -> periods.stream()
                .map(period -> (Temporal) period.getStart())
                .toList());
    }

    private static Optional<Instant> toInstant(Temporal temporal) {
        if (temporal instanceof LocalDate localDate) {
            return Optional.of(localDate.atStartOfDay(ZoneOffset.UTC).toInstant());
        }
        return EventParseUtils.temporalToZonedDateTime(temporal)
            .map(ZonedDateTime::toInstant);
    }
}
