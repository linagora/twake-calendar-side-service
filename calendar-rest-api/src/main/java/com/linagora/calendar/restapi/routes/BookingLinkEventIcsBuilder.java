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

package com.linagora.calendar.restapi.routes;

import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.FunctionalUtils;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.CuType;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.parameter.Rsvp;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.util.RandomUidGenerator;
import net.fortuna.ical4j.util.UidGenerator;
import net.fortuna.ical4j.validate.ValidationResult;

public class BookingLinkEventIcsBuilder {

    private static final String PROD_ID = "-//Twake Calendar//Public Booking//EN";
    private static final String X_PUBLICLY_CREATOR = "X-PUBLICLY-CREATOR";
    private static final String X_OPENPAAS_VIDEOCONFERENCE = "X-OPENPAAS-VIDEOCONFERENCE";
    private static final Transp TRANSP_OPAQUE = new Transp(Transp.VALUE_OPAQUE);
    private static final XProperty X_PROPERTY_PUBLIC = new XProperty("X-PUBLICLY-CREATED", "true");
    private static final String MAIL_TO_PREFIX = "mailto:";

    private final Clock clock;
    private final MeetingConferenceLinkResolver meetingLinkResolver;
    private final UidGenerator uidGenerator;

    @Inject
    public BookingLinkEventIcsBuilder(Clock clock, MeetingConferenceLinkResolver meetingLinkResolver) {
        this(clock, meetingLinkResolver, new RandomUidGenerator());
    }

    BookingLinkEventIcsBuilder(Clock clock, MeetingConferenceLinkResolver meetingLinkResolver, UidGenerator uidGenerator) {
        this.clock = clock;
        this.meetingLinkResolver = meetingLinkResolver;
        this.uidGenerator = uidGenerator;
    }

    public BuildResult build(BookingRequest request, Duration eventDuration) {
        Uid eventUid = uidGenerator.generateUid();
        Optional<URL> maybeMeetingLink = Optional.of(request.visioLink())
            .filter(FunctionalUtils.identityPredicate())
            .map(yes -> meetingLinkResolver.resolve());

        Calendar calendar = new Calendar()
            .withDefaults()
            .withProdId(PROD_ID)
            .withComponent(buildEvent(request, eventUid, eventDuration, maybeMeetingLink))
            .getFluentTarget();

        return new BuildResult(eventUid, calendar, maybeMeetingLink);
    }

    private VEvent buildEvent(BookingRequest request,
                              Uid eventUid,
                              Duration eventDuration,
                              Optional<URL> maybeMeetingLink) {
        ImmutableList.Builder<Property> properties = ImmutableList.<Property>builder()
            .add(eventUid)
            .add(TRANSP_OPAQUE)
            .add(new Summary(request.title()))
            .add(new DtStamp(clock.instant()))
            .add(new DtStart<>(request.slotStartUtc()))
            .add(new net.fortuna.ical4j.model.property.Duration(eventDuration))
            .add(buildOrganizer(request.creator()))
            .add(buildAttendee(request.creator()))
            .addAll(request.additionalAttendees().stream()
                .map(this::buildAttendee)
                .toList());

        if (StringUtils.isNotBlank(request.notes())) {
            properties.add(new Description(request.notes()));
        }

        properties
            .add(new Clazz(Clazz.VALUE_PUBLIC))
            .add(X_PROPERTY_PUBLIC)
            .add(new XProperty(X_PUBLICLY_CREATOR, request.creator().email().asString()));
        maybeMeetingLink.ifPresent(visioLink -> properties.add(new XProperty(X_OPENPAAS_VIDEOCONFERENCE, visioLink.toString())));

        VEvent event = new VEvent(new PropertyList(properties.build()));
        ValidationResult validationResult = event.validate();
        if (validationResult.hasErrors()) {
            throw new IllegalStateException("Generated booking ICS is invalid for eventId " + eventUid.getValue() + ": " + validationResult);
        }
        return event;
    }

    private Attendee buildAttendee(BookingAttendee attendee) {
        return (Attendee) new Attendee(URI.create(MAIL_TO_PREFIX + attendee.email().asString()))
            .withParameter(new Cn(attendee.name()))
            .withParameter(Rsvp.TRUE)
            .withParameter(Role.REQ_PARTICIPANT)
            .withParameter(CuType.INDIVIDUAL)
            .withParameter(PartStat.NEEDS_ACTION)
            .getFluentTarget();
    }

    private Organizer buildOrganizer(BookingAttendee attendee) {
        return (Organizer) new Organizer(URI.create(MAIL_TO_PREFIX + attendee.email().asString()))
            .withParameter(new Cn(attendee.name()))
            .withParameter(Rsvp.FALSE)
            .withParameter(Role.CHAIR)
            .withParameter(CuType.INDIVIDUAL)
            .withParameter(PartStat.NEEDS_ACTION)
            .getFluentTarget();
    }

    public record BuildResult(Uid eventId, Calendar calendar,
                              Optional<URL> visioLink) {

        public byte[] icsBytes() {
            return calendar.toString().getBytes(StandardCharsets.UTF_8);
        }

        public String eventIdAsString() {
            return eventId.getValue();
        }
    }
}
