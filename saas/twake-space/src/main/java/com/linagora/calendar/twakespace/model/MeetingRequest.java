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

package com.linagora.calendar.twakespace.model;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.model.TeamCalendarId;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtEnd;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.XProperty;

// com.twake.space.meeting.requested.v1: a member of a space asks for a meeting in its team calendar.
public record MeetingRequest(String id, MailAddress organizer, TeamCalendarId teamCalendarId, String uid, String title,
                             ZonedDateTime start, ZonedDateTime end, Optional<String> description) {
    public static final String TYPE = "com.twake.space.meeting.requested.v1";

    private static final String CALENDAR_KIND = "calendar";
    private static final String PROD_ID = "-//Twake Calendar//TwakeSpace meeting//EN";
    private static final String VIDEOCONFERENCE = "X-OPENPAAS-VIDEOCONFERENCE";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Command(@JsonProperty(required = true) String id, @JsonProperty(required = true) String type,
                           @JsonProperty(value = "twakeactor", required = true) String actor, @JsonProperty(required = true) Data data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Data(@JsonProperty(required = true) String uid, @JsonProperty(required = true) Container container,
                        @JsonProperty(required = true) String title, @JsonProperty(required = true) OffsetDateTime start,
                        @JsonProperty(required = true) OffsetDateTime end, @JsonProperty(required = true) ZoneId timezone,
                        String description) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Container(@JsonProperty(required = true) String kind, @JsonProperty(required = true) String id) {
    }

    public static MeetingRequest deserialize(byte[] body) {
        try {
            Command command = OBJECT_MAPPER.readValue(body, Command.class);
            Preconditions.checkArgument(TYPE.equals(command.type()), "not a %s: %s", TYPE, command.type());
            Preconditions.checkArgument(CALENDAR_KIND.equals(command.data().container().kind()),
                "the meeting goes in a calendar, not a %s", command.data().container().kind());
            Data data = command.data();
            return new MeetingRequest(command.id(), new MailAddress(command.actor()), new TeamCalendarId(data.container().id()), data.uid(),
                data.title(), data.start().atZoneSameInstant(data.timezone()), data.end().atZoneSameInstant(data.timezone()),
                Optional.ofNullable(StringUtils.trimToNull(data.description())));
        } catch (IOException | AddressException | IllegalArgumentException e) {
            throw new UnprocessableSpaceEventException("Unable to read a meeting request", e);
        }
    }

    public MeetingRequest {
        Preconditions.checkArgument(StringUtils.isNotBlank(uid), "uid must not be empty");
        Preconditions.checkArgument(StringUtils.isNotBlank(title), "title must not be empty");
        Preconditions.checkArgument(end.isAfter(start), "the meeting must end after it starts");
    }

    // No attendee: the team calendar shows the meeting to every member, and no invitation is sent.
    public Calendar asCalendar(URI videoconference, Instant now) {
        VEvent event = new VEvent();
        event.add(new Uid(uid));
        event.add(new DtStamp(now));
        event.add(new DtStart<>(start));
        event.add(new DtEnd<>(end));
        event.add(new Summary(title));
        event.add(new Organizer(URI.create("mailto:" + organizer.asString())));
        event.add(new XProperty(VIDEOCONFERENCE, videoconference.toString()).add(Value.URI));
        description.ifPresent(text -> event.add(new Description(text)));
        return new Calendar()
            .withDefaults()
            .withProdId(PROD_ID)
            .withComponent(TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(start.getZone().getId()).getVTimeZone())
            .withComponent(event)
            .getFluentTarget();
    }
}
