package com.linagora.calendar.amqp;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.linagora.calendar.dav.CalendarUtil;

import net.fortuna.ical4j.model.Calendar;

public record CalendarEventNotificationEmail(@JsonProperty("senderEmail") String senderEmail,
                                             @JsonProperty("recipientEmail") String recipientEmail,
                                             @JsonProperty("method") Method method,
                                             @JsonProperty("event") @JsonDeserialize(using = CalendarEventDeserializer.class) Calendar event,
                                             @JsonProperty("notify") boolean notifyEvent,
                                             @JsonProperty("calendarURI") String calendarURI,
                                             @JsonProperty("eventPath") String eventPath,
                                             @JsonProperty("changes") Changes changes,
                                             @JsonProperty("isNewEvent") Boolean isNewEvent,
                                             @JsonProperty("oldEvent") @JsonDeserialize(using = CalendarEventDeserializer.class) Calendar oldEvent) {
    public enum Method {
        REQUEST, REPLY, CANCEL, COUNTER
    }

    public record Changes(@JsonProperty("dtstart") DateTimeChange dtstart,
                          @JsonProperty("dtend") DateTimeChange dtend,
                          @JsonProperty("summary") StringChange summary) {

    }

    public record DateTimeChange(@JsonProperty("previous") DateTimeValue previous,
                                 @JsonProperty("current") DateTimeValue current) {
    }

    public record DateTimeValue(@JsonProperty("isAllDay") boolean isAllDay,
                                @JsonProperty("date") String date,
                                @JsonProperty("timezone_type") int timezoneType,
                                @JsonProperty("timezone") String timezone) {

    }

    public record StringChange(@JsonProperty("previous") String previous,
                               @JsonProperty("current") String current) {
    }

    public static class CalendarEventDeserializer extends JsonDeserializer<Calendar> {
        @Override
        public Calendar deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String icsString = p.getValueAsString();
            return CalendarUtil.parseIcs(icsString);
        }
    }
}
