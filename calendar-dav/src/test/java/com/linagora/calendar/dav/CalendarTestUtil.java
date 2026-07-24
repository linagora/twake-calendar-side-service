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
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the    *
 *  GNU Affero General Public License for more details.             *
 ********************************************************************/

package com.linagora.calendar.dav;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import com.linagora.calendar.api.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public final class CalendarTestUtil {
    public static final String MASTER_RECURRENCE_KEY = "master";

    private CalendarTestUtil() {
    }

    public static class CalendarExtractor {
        private final Calendar calendar;

        private CalendarExtractor(Calendar calendar) {
            this.calendar = calendar;
        }

        public Property extractProperty(String propertyName) {
            return calendar.getComponent(Component.VEVENT)
                .flatMap(vevent -> vevent.getProperty(propertyName))
                .map(property -> (Property) property)
                .orElseThrow(() -> new AssertionError("Expected VEVENT " + propertyName + " to be present"));
        }

        public String extractPropertyValue(String propertyName) {
            return extractProperty(propertyName).getValue();
        }

        public VEvent extractEventBySummary(String summary) {
            return calendar.getComponents(Component.VEVENT).stream()
                .map(VEvent.class::cast)
                .filter(vevent -> vevent.getProperty(Property.SUMMARY)
                    .map(Property::getValue)
                    .filter(summary::equals)
                    .isPresent())
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected VEVENT with summary " + summary + " to be present"));
        }

        public Component extractEventComponent(Optional<String> recurrenceId) {
            return calendar.getComponents(Component.VEVENT).stream()
                .filter(vevent -> matchesRecurrenceId(vevent, recurrenceId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected VEVENT to be present for recurrence " + recurrenceId));
        }

        public Property extractEventProperty(Optional<String> recurrenceId, String propertyName) {
            Component eventComponent = extractEventComponent(recurrenceId);
            return findProperty(eventComponent, propertyName)
                .orElseThrow(() -> new AssertionError("Expected " + propertyName + " to be present for recurrence " + recurrenceId));
        }

        public String extractEventPropertyValue(Optional<String> recurrenceId, String propertyName) {
            return extractEventProperty(recurrenceId, propertyName).getValue();
        }

        public PartStat extractAttendeePartStat(String attendeeEmail) {
            return CalendarTestUtil.getAttendeePartStat(calendar, attendeeEmail);
        }

        public Calendar asCalendar() {
            return calendar;
        }

        private static boolean matchesRecurrenceId(Component eventComponent, Optional<String> recurrenceId) {
            return eventComponent.getProperty(Property.RECURRENCE_ID)
                .map(Property::getValue)
                .equals(recurrenceId);
        }

        private static Optional<Property> findProperty(Component component, String propertyName) {
            return component.getProperty(propertyName)
                .or(() -> findSubComponentProperty(component, propertyName));
        }

        private static Optional<Property> findSubComponentProperty(Component component, String propertyName) {
            if (!(component instanceof ComponentContainer<?> componentContainer)) {
                return Optional.empty();
            }
            return componentContainer.getComponentList().getAll().stream()
                .flatMap(subComponent -> subComponent.getProperty(propertyName).stream())
                .findFirst();
        }
    }

    public static CalendarExtractor toExtractor(String icsContent) {
        return toExtractor(icsContent.getBytes(StandardCharsets.UTF_8));
    }

    public static CalendarExtractor toExtractor(byte[] icsContent) {
        return new CalendarExtractor(parseIcs(icsContent));
    }

    public static Calendar parseIcs(String icsContent) {
        return CalendarUtil.parseIcs(icsContent);
    }

    public static Calendar parseIcs(byte[] icsContent) {
        return CalendarUtil.parseIcs(icsContent);
    }

    public static Calendar parseIcsAndSanitize(String icsContent) {
        return parseIcsAndSanitize(icsContent, Property.PRODID, Property.DTSTAMP);
    }

    public static Calendar parseIcsAndSanitize(String icsContent, String... ignoredProperties) {
        Calendar calendar = parseIcs(icsContent);
        removeAllProperties(calendar, ignoredProperties);
        return calendar;
    }

    public static void removeAllProperties(Calendar calendar, String... propertyNames) {
        for (String name : propertyNames) {
            calendar.removeAll(name);
            calendar.getComponents().forEach(component -> component.removeAll(name));
        }
    }

    public static void removePropertiesFromComponents(Calendar calendar, String componentName, String... propertyNames) {
        calendar.getComponents().stream()
            .filter(ComponentContainer.class::isInstance)
            .flatMap(component -> ((ComponentContainer<?>) component).getComponentList().getAll().stream())
            .filter(component -> componentName.equals(component.getName()))
            .forEach(component -> {
                for (String propertyName : propertyNames) {
                    component.removeAll(propertyName);
                }
            });
    }

    public static void removeParticipantScheduleStatus(Calendar calendar) {
        calendar.getComponents(Component.VEVENT).forEach(vevent -> {
            vevent.getProperties(Property.ATTENDEE)
                .forEach(attendee -> removeParameter(attendee, Parameter.SCHEDULE_STATUS));
            vevent.getProperties(Property.ORGANIZER)
                .forEach(organizer -> removeParameter(organizer, Parameter.SCHEDULE_STATUS));
        });
    }

    public static PartStat getAttendeePartStat(String icsContent, String attendeeEmail) {
        return getAttendeePartStat(parseIcs(icsContent), attendeeEmail);
    }

    public static PartStat getAttendeePartStat(Calendar calendar, String attendeeEmail) {
        return findAttendeeProperties(calendar, attendeeEmail)
            .findFirst()
            .map(property -> partStatOf(property, attendeeEmail))
            .orElseThrow(() -> new AssertionError("Attendee not found in calendar: " + attendeeEmail));
    }

    public static Map<String, PartStat> getRecurringAttendeePartStats(String icsContent, String attendeeEmail) {
        return getRecurringAttendeePartStats(parseIcs(icsContent), attendeeEmail);
    }

    public static Map<String, PartStat> getRecurringAttendeePartStats(Calendar calendar, String attendeeEmail) {
        String mailAddress = "mailto:" + attendeeEmail;
        Map<String, PartStat> result = new LinkedHashMap<>();
        for (Component vevent : calendar.getComponents(Component.VEVENT)) {
            vevent.getProperties(Property.ATTENDEE).stream()
                .filter(property -> mailAddress.equalsIgnoreCase(property.getValue()))
                .findFirst()
                .ifPresent(property -> {
                    String key = vevent.getProperty(Property.RECURRENCE_ID)
                        .map(Property::getValue)
                        .orElse(MASTER_RECURRENCE_KEY);
                    result.put(key, partStatOf(property, attendeeEmail));
                });
        }
        if (result.isEmpty()) {
            throw new AssertionError("Attendee not found in calendar: " + attendeeEmail);
        }
        return result;
    }

    public static String withAttendeePartStat(String icsContent, String attendeeEmail, PartStat partStat) {
        Calendar calendar = parseIcs(icsContent);
        setAttendeePartStat(calendar, attendeeEmail, partStat);
        return calendar.toString();
    }

    public static void setAttendeePartStat(Calendar calendar, String attendeeEmail, PartStat partStat) {
        List<Property> attendees = findAttendeeProperties(calendar, attendeeEmail).toList();
        if (attendees.isEmpty()) {
            throw new AssertionError("Attendee not found in calendar: " + attendeeEmail);
        }
        attendees.forEach(attendee -> {
            removeParameter(attendee, Parameter.PARTSTAT);
            attendee.add(partStat);
        });
    }

    public static void removeAttendeePartStat(Calendar calendar, String attendeeEmail) {
        findAttendeeProperties(calendar, attendeeEmail)
            .forEach(attendee -> removeParameter(attendee, Parameter.PARTSTAT));
    }

    private static Stream<Property> findAttendeeProperties(Calendar calendar, String attendeeEmail) {
        String mailAddress = "mailto:" + attendeeEmail;
        return calendar.getComponents(Component.VEVENT).stream()
            .flatMap(vevent -> vevent.getProperties(Property.ATTENDEE).stream())
            .filter(property -> mailAddress.equalsIgnoreCase(property.getValue()));
    }

    private static PartStat partStatOf(Property attendee, String email) {
        return attendee.getParameter(Parameter.PARTSTAT)
            .map(parameter -> (PartStat) parameter)
            .orElseThrow(() -> new AssertionError("Missing PARTSTAT for attendee " + email));
    }

    private static void removeParameter(Property property, String parameterName) {
        property.getParameter(parameterName).ifPresent(property::remove);
    }
}
