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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ContentHandlerContext;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.util.CompatibilityHints;
import net.fortuna.ical4j.util.MapTimeZoneCache;

public class CalendarUtil {

    public static class CustomizedTimeZoneRegistry extends TimeZoneRegistryImpl {

        @Override
        public ZoneId getZoneId(String tzId) {
            try {
                // Attempt to get the zone ID from the parent class first
                return super.getZoneId(tzId);
            } catch (DateTimeException e) {
                // If it fails, we try to get the global zone ID
                if (e.getMessage().contains("Unknown timezone identifier")) {
                    return TimeZoneRegistry.getGlobalZoneId(tzId);
                }
                throw e;
            }
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
            new ContentHandlerContext().withSuppressInvalidProperties(true),
            new CustomizedTimeZoneRegistry());
        try {
            byte[] sanitized = dropVTimeZonesWithoutObservance(new String(icsContent, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
            ByteArrayInputStream inputStream = new ByteArrayInputStream(sanitized);
            return builder.build(inputStream);
        } catch (IOException e) {
            throw new RuntimeException("Error while reading calendar input", e);
        } catch (ParserException e) {
            throw new RuntimeException("Error while parsing ICal object", e);
        }
    }

    /**
     * Some clients (e.g. Sabre) may emit a VTIMEZONE without any STANDARD or DAYLIGHT observance.
     * Such a component is invalid per RFC 5545 and makes ical4j fail with a NullPointerException
     * ("Cannot invoke Observance.getRequiredProperty(...)") while building the calendar.
     * Since an observance-less VTIMEZONE carries no offset information, it can safely be dropped:
     * the TZID referenced by the events is still resolved through the timezone registry.
     */
    private static String dropVTimeZonesWithoutObservance(String icsContent) {
        if (!icsContent.toUpperCase(Locale.US).contains("BEGIN:VTIMEZONE")) {
            return icsContent;
        }
        String[] lines = icsContent.split("\r\n|\r|\n", -1);
        StringBuilder result = new StringBuilder(icsContent.length());
        List<String> currentBlock = null;
        boolean hasObservance = false;
        boolean dropped = false;
        for (String line : lines) {
            String directive = line.trim().toUpperCase(Locale.US);
            if (directive.equals("BEGIN:VTIMEZONE")) {
                currentBlock = new ArrayList<>();
                hasObservance = false;
                currentBlock.add(line);
            } else if (currentBlock != null) {
                currentBlock.add(line);
                if (directive.equals("BEGIN:STANDARD") || directive.equals("BEGIN:DAYLIGHT")) {
                    hasObservance = true;
                } else if (directive.equals("END:VTIMEZONE")) {
                    if (hasObservance) {
                        currentBlock.forEach(kept -> result.append(kept).append("\r\n"));
                    } else {
                        dropped = true;
                    }
                    currentBlock = null;
                }
            } else {
                result.append(line).append("\r\n");
            }
        }
        if (currentBlock != null) {
            currentBlock.forEach(kept -> result.append(kept).append("\r\n"));
        }
        if (!dropped) {
            return icsContent;
        }
        return result.toString();
    }

    public static Calendar withSingleVEvent(Calendar template, VEvent vevent) {
        Calendar copiedCalendar = template.copy();
        copiedCalendar.getComponents(Component.VEVENT).stream()
            .map(VEvent.class::cast)
            .toList()
            .forEach(copiedCalendar::remove);
        copiedCalendar.add(vevent.copy());
        return copiedCalendar;
    }
}
