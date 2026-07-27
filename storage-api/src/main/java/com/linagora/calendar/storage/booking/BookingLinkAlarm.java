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

package com.linagora.calendar.storage.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAmount;
import java.util.List;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;

import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.property.Trigger;

/**
 * An email reminder on the event created from a booking link: {@code trigger} is the VALARM TRIGGER duration,
 * relative to the event start, e.g. {@code -PT10M} for ten minutes before.
 *
 * <p>The trigger is held as its RFC 5545 dur-value spelling, ical4j doing the parsing: {@code -P1W} is valid
 * there while {@link Duration#parse(CharSequence)} rejects the week designator.
 */
public record BookingLinkAlarm(String trigger) {

    private static final ZonedDateTime REFERENCE = Instant.EPOCH.atZone(ZoneOffset.UTC);

    public BookingLinkAlarm {
        Preconditions.checkArgument(StringUtils.isNotBlank(trigger), "'alarm' must not be blank");
        trigger = StringUtils.upperCase(StringUtils.trim(trigger), Locale.US);

        ZonedDateTime alarmTime = REFERENCE.plus(parseTemporalAmount(trigger));
        Preconditions.checkArgument(!alarmTime.isAfter(REFERENCE),
            "'alarm' trigger must not be positive: an alarm fires ahead of the event start");
    }

    public TemporalAmount triggerAsTemporalAmount() {
        return parseTemporalAmount(trigger);
    }

    private static TemporalAmount parseTemporalAmount(String trigger) {
        try {
            VAlarm alarm = new VAlarm();
            alarm.add(new Trigger(new ParameterList(List.of()), trigger));
            return alarm.getProperty(Property.TRIGGER)
                .map(property -> ((Trigger) property).getDuration())
                .orElseThrow();
        } catch (Exception e) {
            throw new IllegalArgumentException(invalidTriggerMessage(trigger), e);
        }
    }

    private static String invalidTriggerMessage(String trigger) {
        return "Wrong alarm format: '" + trigger + "'";
    }
}
