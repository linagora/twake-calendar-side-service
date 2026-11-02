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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.calendar.api;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoField;
import java.time.temporal.Temporal;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ContentHandlerContext;
import net.fortuna.ical4j.data.DefaultPropertyFactorySupplier;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyFactory;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.component.CalendarComponent;
import net.fortuna.ical4j.model.component.Observance;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.component.VTimeZone;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.RRule;
import net.fortuna.ical4j.model.property.Version;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.transform.recurrence.Frequency;
import net.fortuna.ical4j.util.CompatibilityHints;
import net.fortuna.ical4j.util.MapTimeZoneCache;

public class CalendarUtil {

    /**
     * ical4j allocates a global zone id to each registered VTIMEZONE, out of a JVM wide pool of 1500 ids
     * (`net.fortuna.ical4j.timezone.id.pool.size`) that are only given back once the registry is garbage collected.
     * Parsed calendars reference their registry, thus a burst of parsing between two garbage collections empties the
     * pool and any subsequent VTIMEZONE fails to parse.
     *
     * <p>To avoid this, VTIMEZONE whose TZID is known by the JVM, either directly or through the ical4j aliases (Windows
     * zone names...), are not registered: they resolve to the JVM zone. Only custom TZIDs are registered and resolved
     * from their own definition.
     *
     * <p>Registering a VTIMEZONE makes ical4j expand every observance RRULE from its DTSTART up to now. Custom zones whose
     * observances would yield too many transitions (sub-daily rules, decades of monthly ones...) are not registered either:
     * their TZID is then handled like any unknown TZID. So are the custom zones beyond the first
     * {@link #MAX_CUSTOM_TIMEZONES} of a calendar, as each takes an id from the JVM wide pool.
     */
    public static class CustomizedTimeZoneRegistry implements TimeZoneRegistry {
        private static final Logger LOGGER = LoggerFactory.getLogger(CustomizedTimeZoneRegistry.class);
        @VisibleForTesting
        static final long MAX_OBSERVANCE_TRANSITIONS = 20_000;
        @VisibleForTesting
        static final int MAX_CUSTOM_TIMEZONES = 20;
        private static final int MIN_EXPANDED_YEARS = 5;
        private static final int MAX_DAYS_PER_YEAR = 366;
        private static final int MAX_WEEKDAYS_PER_MONTH = 5;
        private static final int MONTHS_PER_YEAR = 12;

        private final TimeZoneRegistryImpl delegate = new TimeZoneRegistryImpl();
        private int registeredCount = 0;

        @Override
        public void register(TimeZone timezone) {
            if (shouldRegister(timezone)) {
                delegate.register(timezone);
            }
        }

        @Override
        public void register(TimeZone timezone, boolean update) {
            if (shouldRegister(timezone)) {
                delegate.register(timezone, update);
            }
        }

        private boolean shouldRegister(TimeZone timezone) {
            if (isGloballyKnown(timezone.getID())) {
                return false;
            }
            if (registeredCount >= MAX_CUSTOM_TIMEZONES) {
                LOGGER.info("Ignoring VTIMEZONE {}: more than {} custom timezones in the calendar", timezone.getID(), MAX_CUSTOM_TIMEZONES);
                return false;
            }
            if (!hasBoundedObservances(timezone.getVTimeZone())) {
                LOGGER.info("Ignoring VTIMEZONE {}: its observances recur too often to be expanded", timezone.getID());
                return false;
            }
            registeredCount++;
            return true;
        }

        /**
         * Upper bound of the transitions ical4j would generate, without expanding them. Only yearly and monthly rules
         * without sub-daily parts are accepted: every real world VTIMEZONE recurs yearly.
         */
        @VisibleForTesting
        static boolean hasBoundedObservances(VTimeZone vTimeZone) {
            int currentYear = Year.now(ZoneOffset.UTC).getValue();
            long transitions = 0;
            for (Observance observance : vTimeZone.getObservances()) {
                for (RRule<?> rRule : observance.<RRule<?>>getProperties(Property.RRULE)) {
                    OptionalLong perYear = maxOccurrencesPerYear(rRule.getRecur());
                    if (perYear.isEmpty()) {
                        return false;
                    }
                    transitions += perYear.getAsLong() * expandedYears(observance, currentYear);
                    if (transitions > MAX_OBSERVANCE_TRANSITIONS) {
                        return false;
                    }
                }
            }
            return true;
        }

        private static OptionalLong maxOccurrencesPerYear(Recur<?> recur) {
            boolean yearly = recur.getFrequency() == Frequency.YEARLY;
            if (!yearly && recur.getFrequency() != Frequency.MONTHLY) {
                return OptionalLong.empty();
            }
            if (!recur.getHourList().isEmpty() || !recur.getMinuteList().isEmpty() || !recur.getSecondList().isEmpty()
                || !recur.getYearDayList().isEmpty() || !recur.getWeekNoList().isEmpty()) {
                return OptionalLong.empty();
            }
            long daysPerMonth = 1;
            if (!recur.getMonthDayList().isEmpty()) {
                daysPerMonth = recur.getMonthDayList().size();
            } else if (!recur.getDayList().isEmpty()) {
                daysPerMonth = recur.getDayList().stream()
                    .mapToLong(weekDay -> weekDay.getOffset() == 0 ? MAX_WEEKDAYS_PER_MONTH : 1)
                    .sum();
            }
            long months = MONTHS_PER_YEAR;
            if (!recur.getMonthList().isEmpty()) {
                months = recur.getMonthList().size();
            } else if (yearly && recur.getMonthDayList().isEmpty() && recur.getDayList().isEmpty()) {
                months = 1;
            }
            return OptionalLong.of(Math.min(MAX_DAYS_PER_YEAR, months * daysPerMonth));
        }

        private static long expandedYears(Observance observance, int currentYear) {
            return observance.<DtStart<Temporal>>getProperty(Property.DTSTART)
                .map(DtStart::getDate)
                .filter(date -> date.isSupported(ChronoField.YEAR))
                .map(date -> (long) currentYear - date.get(ChronoField.YEAR) + 1)
                .map(years -> Math.max(years, MIN_EXPANDED_YEARS))
                .orElse((long) MIN_EXPANDED_YEARS);
        }

        private boolean isGloballyKnown(String tzId) {
            try {
                TimeZoneRegistry.getGlobalZoneId(tzId);
                return true;
            } catch (DateTimeException e) {
                return false;
            }
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public TimeZone getTimeZone(String id) {
            return delegate.getTimeZone(id);
        }

        @Override
        public Map<String, ZoneRules> getZoneRules() {
            return delegate.getZoneRules();
        }

        @Override
        public ZoneId getZoneId(String tzId) {
            try {
                // Attempt to get the zone ID from the registered timezones first
                return delegate.getZoneId(tzId);
            } catch (DateTimeException e) {
                // If it fails, we try to get the global zone ID
                if (e.getMessage().contains("Unknown timezone identifier")) {
                    return TimeZoneRegistry.getGlobalZoneId(tzId);
                }
                throw e;
            }
        }

        @Override
        public String getTzId(String zoneId) {
            return delegate.getTzId(zoneId);
        }
    }

    static {
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_PARSING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_UNFOLDING, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_RELAXED_VALIDATION, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_OUTLOOK_COMPATIBILITY, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_NOTES_COMPATIBILITY, true);
        CompatibilityHints.setHintEnabled(CompatibilityHints.KEY_VCARD_COMPATIBILITY, true);

        System.setProperty("net.fortuna.ical4j.timezone.cache.impl", MapTimeZoneCache.class.getName());
    }

    public static Calendar parseIcs(String icsContent) {
        return parseIcs(icsContent.getBytes(StandardCharsets.UTF_8));
    }

    public static Calendar parseIcs(byte[] icsContent) {
        CalendarBuilder builder = new CalendarBuilder(
            CalendarParserFactory.getInstance().get(),
            new ContentHandlerContext()
                .withSuppressInvalidProperties(true)
                .withPropertyFactorySupplier(CalendarUtil::propertyFactories),
            new CustomizedTimeZoneRegistry());
        try {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(icsContent);
            return builder.build(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Error while reading calendar input", e);
        } catch (ParserException e) {
            throw new RuntimeException("Error while parsing ICal object", e);
        }
    }

    /**
     * The ical4j property factories, VERSION being parsed by {@link LenientVersion}. Factories are looked up in
     * order, the first one supporting a property name wins.
     */
    private static List<PropertyFactory<?>> propertyFactories() {
        return Stream.concat(Stream.of(new LenientVersion.Factory()),
                new DefaultPropertyFactorySupplier().get().stream())
            .toList();
    }

    /**
     * Returns a deep copy of the given calendar.
     *
     * <p>Unlike {@link Calendar#copy()}, which re-creates every property through its factory and thus fails on
     * properties kept by relaxed parsing (unknown properties, malformed values), this re-parses the serialized calendar.
     *
     * @throws IllegalArgumentException when the calendar cannot be copied
     */
    public static Calendar copyOf(Calendar calendar) {
        try {
            return parseIcs(calendar.toString());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Could not copy calendar: " + e.getMessage(), e);
        }
    }

    /**
     * Returns a copy of the given calendar carrying the supplied iTIP METHOD.
     *
     * <p>Calendar objects stored on the CalDAV server must not carry a METHOD property, thus it needs to be
     * added back when the very same calendar is turned into an iTIP message (eg a {@code text/calendar} MIME part).
     * Mail clients rely on it to display the event invitation widget.
     *
     * <p>The supplied calendar is left untouched: its property and component lists are immutable and shared.
     */
    public static Calendar withMethod(Calendar calendar, Method method) {
        Calendar copiedCalendar = new Calendar(calendar.getPropertyList(), calendar.getComponentList());
        copiedCalendar.removeAll(Property.METHOD);
        copiedCalendar.add(method);
        return copiedCalendar;
    }

    public static Calendar withSingleVEvent(Calendar template, VEvent vevent) {
        return withVEvents(template, List.of(vevent));
    }

    /**
     * Returns a copy of the given calendar whose VEVENTs are replaced by the supplied ones.
     *
     * <p>Calendar level properties and time zones of the template are kept, as VEVENTs reference them.
     *
     * <p>The template and the supplied VEVENTs are shared rather than deep copied: ical4j's {@code copy()}
     * re-creates each property through its factory, which fails on unknown properties and on some leniently
     * parsed values.
     */
    public static Calendar withVEvents(Calendar template, List<VEvent> vEvents) {
        Stream<CalendarComponent> nonVEventComponents = template.getComponents().stream()
            .filter(component -> !Component.VEVENT.equals(component.getName()));
        List<CalendarComponent> components = Stream.concat(nonVEventComponents, vEvents.stream())
            .toList();
        return new Calendar(template.getPropertyList(), new ComponentList<>(components));
    }

    /**
     * Deep copies the given VEVENT, alarms included.
     *
     * <p>Unlike {@link VEvent#copy()}, this does not fail on properties unknown to ical4j (eg {@code FOOBAR:baz}):
     * relaxed parsing turns them into {@link XProperty} whose factory based copy throws an
     * {@link UnsupportedOperationException}. Nor does it fail on properties whose leniently parsed value their
     * factory cannot read back: those are shared with the original VEVENT, so that they keep their type.
     */
    public static VEvent copy(VEvent vEvent) {
        return new VEvent(copyProperties(vEvent.getPropertyList()),
            new ComponentList<>(vEvent.getAlarms().stream()
                .map(CalendarUtil::copy)
                .toList()));
    }

    private static VAlarm copy(VAlarm vAlarm) {
        return new VAlarm(copyProperties(vAlarm.getPropertyList()));
    }

    private static PropertyList copyProperties(PropertyList properties) {
        return new PropertyList(properties.getAll().stream()
            .map(CalendarUtil::copy)
            .toList());
    }

    /**
     * ical4j's {@link Property#copy()} drops the group prefix (eg {@code X-APPLE} in {@code X-APPLE.FOO}) of every
     * property, and throws on {@link XProperty} whose name does not start with {@code X-}.
     */
    private static Property copy(Property property) {
        Property copy = copyIgnoringPrefix(property);
        copy.setPrefix(property.getPrefix());
        return copy;
    }

    private static Property copyIgnoringPrefix(Property property) {
        if (property instanceof XProperty) {
            return asXProperty(property);
        }
        if (property instanceof Version) {
            return new LenientVersion(property.getParameterList(), property.getValue());
        }
        try {
            return property.copy();
        } catch (RuntimeException e) {
            // Leniently parsed values (eg an EXDATE with month 13, a DTSTART with a TZID and no value) can not be
            // re-parsed by the factory: share the original property instead
            return property;
        }
    }

    private static XProperty asXProperty(Property property) {
        return new XProperty(property.getName(), property.getParameterList(), property.getValue());
    }
}
