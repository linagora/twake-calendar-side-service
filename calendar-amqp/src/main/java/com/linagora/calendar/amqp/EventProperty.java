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

import static java.time.ZoneOffset.UTC;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAmount;
import java.util.Optional;
import java.util.function.Function;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.field.address.LenientAddressParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.eventsearch.EventUid;

import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.property.Duration;

public class EventProperty {

    public static final String UID_PROPERTY = "uid";
    public static final String DTSTART_PROPERTY = "dtstart";
    public static final String DTEND_PROPERTY = "dtend";
    public static final String DTSTAMP_PROPERTY = "dtstamp";
    public static final String CLASS_PROPERTY = "class";
    public static final String SUMMARY_PROPERTY = "summary";
    public static final String DESCRIPTION_PROPERTY = "description";
    public static final String LOCATION_PROPERTY = "location";
    public static final String ORGANIZER_PROPERTY = "organizer";
    public static final String SEQUENCE_PROPERTY = "sequence";
    public static final String ATTENDEE_PROPERTY = "attendee";
    public static final String RECURRENCE_ID_PROPERTY = "recurrence-id";
    public static final String RRULE_PROPERTY = "rrule";
    public static final String DURATION_PROPERTY = "duration";

    protected final String name;
    protected final JsonNode attributes;
    protected final String valueType;
    protected final String value;

    public EventProperty(String name, JsonNode attributes, String valueType, String value) {
        this.name = name;
        this.attributes = attributes;
        this.valueType = valueType;
        this.value = value;
    }

    public static class AttendeeProperty extends EventProperty {
        private final String cn;
        private final MailAddress mailAddress;
        private final String cutype;

        public AttendeeProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            assertCalAddress(base.valueType);
            this.mailAddress = calAddressToMailAddress.apply(base.value);
            this.cn = extractCN.apply(base.attributes);
            this.cutype = Optional.ofNullable(base.attributes.get("cutype"))
                .map(JsonNode::asText).orElse(null);
        }

        public String getCn() {
            return cn;
        }

        public MailAddress getMailAddress() {
            return mailAddress;
        }

        public String getCutype() {
            return cutype;
        }
    }

    public static class OrganizerProperty extends EventProperty {
        private final String cn;
        private final MailAddress mailAddress;

        public OrganizerProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            assertCalAddress(base.valueType);
            this.mailAddress = calAddressToMailAddress.apply(base.value);
            this.cn = extractCN.apply(base.attributes);
        }

        public String getCn() {
            return cn;
        }

        public MailAddress getMailAddress() {
            return mailAddress;
        }
    }

    public static class DtStampProperty extends EventProperty {
        private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy'-'MM'-'dd'T'HH':'mm[':'ss[X]]")
            .withZone(UTC);

        private final Instant dtStamp;

        public DtStampProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            if (!Strings.CS.equals(base.valueType, "date-time")) {
                throw new CalendarEventDeserializeException("Invalid value type for date property, expected 'date-time' but got " + base.valueType);
            }
            this.dtStamp = formatter.parse(base.value, Instant::from);
        }

        public Instant getDtStamp() {
            return dtStamp;
        }
    }

    public static class DateProperty extends EventProperty {
        private static final ZoneId UTC = ZoneId.of("UTC");
        private final Instant date;

        public DateProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            date = parseDate();
        }

        private Instant parseDate() {
            return switch (valueType) {
                case "date" -> LocalDate.from(DateTimeFormatter.ISO_LOCAL_DATE.parse(value)).atStartOfDay()
                    .toInstant(ZoneOffset.UTC);
                case "date-time" -> {
                    try {
                        yield OffsetDateTime.parse(value).toInstant();
                    } catch (DateTimeParseException e1) {
                        ZoneId zoneId = Optional.ofNullable(attributes.get("tzid"))
                            .map(JsonNode::asText)
                            .map(text -> TimeZone.getTimeZone(text).toZoneId())
                            .orElse(UTC);
                        yield ZonedDateTime.of(LocalDateTime.parse(value), zoneId).toInstant();
                    }
                }
                default -> throw new CalendarEventDeserializeException("Invalid date format: " + valueType);
            };
        }

        public Instant getDate() {
            return date;
        }
    }

    public static class DurationProperty extends EventProperty {
        private final TemporalAmount duration;

        public DurationProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            duration = parseDuration();
        }

        private TemporalAmount parseDuration() {
            return new Duration(value).getDuration();
        }

        public TemporalAmount getDuration() {
            return duration;
        }
    }

    public static class EventUidProperty extends EventProperty {
        private final EventUid eventUid;

        public EventUidProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            Preconditions.checkArgument(Strings.CS.equals(EventProperty.UID_PROPERTY, base.name));
            this.eventUid = new EventUid(base.value);
        }

        public EventUid getEventUid() {
            return eventUid;
        }
    }

    public static class SequenceProperty extends EventProperty {
        private static final String INTEGER = "integer";

        private final Integer sequence;

        public SequenceProperty(EventProperty base) {
            super(base.name, base.attributes, base.valueType, base.value);
            if (!INTEGER.equalsIgnoreCase(base.valueType)) {
                throw new CalendarEventDeserializeException("Invalid value type for sequence property, expected 'integer' but got " + base.valueType);
            }

            this.sequence = Optional.ofNullable(base.value)
                .map(Integer::valueOf)
                .orElse(null);
        }

        public Integer getSequence() {
            return sequence;
        }
    }

    static void assertCalAddress(String valueType) {
        Preconditions.checkArgument("cal-address".equals(valueType),
            "Invalid value type for cal-address property, expected 'cal-address' but got '%s'", valueType);
    }

    static final Function<String, MailAddress> calAddressToMailAddress = (String calAddress) -> {
        try {
            return switch (LenientAddressParser.DEFAULT.parseAddress(Strings.CI.remove(calAddress, "mailto:"))) {
                case Mailbox mailbox -> new MailAddress(mailbox.getAddress());
                case null, default -> throw new CalendarEventDeserializeException("Unable to parse mail address from calendar address: " + calAddress);
            };
        } catch (AddressException e) {
            throw new CalendarEventDeserializeException("Unable to parse mail address from calendar address", e);
        }
    };

    static final Function<JsonNode, String> extractCN = (JsonNode attributes) -> {
        if (attributes.has("cn")) {
            return attributes.get("cn").asText();
        }
        return null;
    };

}
