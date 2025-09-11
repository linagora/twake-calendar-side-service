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

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.data.CalendarParserFactory;
import net.fortuna.ical4j.data.ContentHandlerContext;
import net.fortuna.ical4j.data.ParserException;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryImpl;
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
            new ContentHandlerContext().withSupressInvalidProperties(true),
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
}
