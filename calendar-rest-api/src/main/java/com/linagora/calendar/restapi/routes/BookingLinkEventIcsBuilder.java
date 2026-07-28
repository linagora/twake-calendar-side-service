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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.FunctionalUtils;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.booking.BookingLinkAlarm;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.booking.EventTransparency;
import com.linagora.calendar.storage.booking.EventVisibility;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VAlarm;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.CuType;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.parameter.Rsvp;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.property.Action;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.Clazz;
import net.fortuna.ical4j.model.property.Description;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.DtStart;
import net.fortuna.ical4j.model.property.Location;
import net.fortuna.ical4j.model.property.Organizer;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Summary;
import net.fortuna.ical4j.model.property.Transp;
import net.fortuna.ical4j.model.property.Trigger;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.XProperty;
import net.fortuna.ical4j.util.RandomUidGenerator;
import net.fortuna.ical4j.util.UidGenerator;
import net.fortuna.ical4j.validate.ValidationResult;

public class BookingLinkEventIcsBuilder {

    private static final String PROD_ID = "-//Twake Calendar//Public Booking//EN";
    private static final String X_PUBLICLY_CREATOR = "X-PUBLICLY-CREATOR";
    private static final String X_OPENPAAS_BOOKING_LINK = "X-OPENPAAS-BOOKING-LINK";
    private static final String X_OPENPAAS_VIDEOCONFERENCE = "X-OPENPAAS-VIDEOCONFERENCE";
    private static final String VISIO_DESCRIPTION_PREFIX = "Visio: ";
    private static final XProperty X_PROPERTY_PUBLIC = new XProperty("X-PUBLICLY-CREATED", "TRUE").add(Value.BOOLEAN);
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

    /**
     * The booking-link-level event properties that the booker never fills in: they are configured on the link and
     * carried onto the created event.
     */
    public record BookingEventOptions(Optional<String> location,
                                      Optional<EventVisibility> visibility,
                                      Optional<EventTransparency> transparency,
                                      List<BookingAttendee> resources,
                                      Optional<BookingLinkAlarm> alarm) {
        public static BookingEventOptions none() {
            return new BookingEventOptions(Optional.empty(), Optional.empty(), Optional.empty(), List.of(), Optional.empty());
        }
    }

    public BuildResult build(BookingRequest request, BookingAttendee organizer, Duration eventDuration, BookingLinkPublicId bookingLinkPublicId) {
        return build(request, organizer, eventDuration, bookingLinkPublicId, false);
    }

    public BuildResult build(BookingRequest request, BookingAttendee organizer, Duration eventDuration, BookingLinkPublicId bookingLinkPublicId, boolean autoAccept) {
        return build(request, organizer, List.of(), eventDuration, bookingLinkPublicId, autoAccept);
    }

    public BuildResult build(BookingRequest request,
                             BookingAttendee organizer,
                             List<BookingAttendee> extraAttendees,
                             Duration eventDuration,
                             BookingLinkPublicId bookingLinkPublicId,
                             boolean autoAccept) {
        return build(request, organizer, extraAttendees, eventDuration, bookingLinkPublicId, autoAccept, BookingEventOptions.none());
    }

    public BuildResult build(BookingRequest request,
                             BookingAttendee organizer,
                             List<BookingAttendee> extraAttendees,
                             Duration eventDuration,
                             BookingLinkPublicId bookingLinkPublicId,
                             boolean autoAccept,
                             BookingEventOptions options) {
        Uid eventUid = uidGenerator.generateUid();
        Optional<URL> maybeMeetingLink = Optional.of(request.visioLink())
            .filter(FunctionalUtils.identityPredicate())
            .map(yes -> meetingLinkResolver.resolve());

        Calendar calendar = new Calendar()
            .withDefaults()
            .withProdId(PROD_ID)
            .withComponent(buildEvent(request, organizer, extraAttendees, eventUid, eventDuration, maybeMeetingLink, bookingLinkPublicId, autoAccept, options))
            .getFluentTarget();

        return new BuildResult(eventUid, calendar, maybeMeetingLink);
    }

    private VEvent buildEvent(BookingRequest request,
                              BookingAttendee organizer,
                              List<BookingAttendee> extraAttendees,
                              Uid eventUid,
                              Duration eventDuration,
                              Optional<URL> maybeMeetingLink,
                              BookingLinkPublicId bookingLinkPublicId,
                              boolean autoAccept,
                              BookingEventOptions options) {
        Transp transp = new Transp(options.transparency().map(EventTransparency::value).orElse(Transp.VALUE_OPAQUE));
        Clazz clazz = new Clazz(options.visibility().map(EventVisibility::value).orElse(Clazz.VALUE_PUBLIC));

        List<BookingAttendee> newExtraAttendees = newExtraAttendees(request, extraAttendees);

        ImmutableList.Builder<Property> properties = ImmutableList.<Property>builder()
            .add(eventUid)
            .add(transp)
            .add(clazz)
            .add(new Summary(request.title()))
            .add(new DtStamp(clock.instant()))
            .add(new Sequence(0))
            .add(new DtStart<>(request.slotStartUtc()))
            .add(new net.fortuna.ical4j.model.property.Duration(eventDuration))
            .addAll(buildOrganizer(organizer, autoAccept))
            .add(buildAttendee(request.creator()))
            .addAll(request.additionalAttendees().stream()
                .map(this::buildAttendee)
                .toList())
            .addAll(newExtraAttendees.stream()
                .map(this::buildExtraAttendee)
                .toList())
            .addAll(options.resources().stream()
                .map(this::buildResourceAttendee)
                .toList());

        buildDescription(request.notes(), maybeMeetingLink)
            .map(Description::new)
            .ifPresent(properties::add);

        options.location()
            .map(Location::new)
            .ifPresent(properties::add);

        properties
            .add(X_PROPERTY_PUBLIC)
            .add(new XProperty(X_PUBLICLY_CREATOR, request.creator().email().asString()))
            .add(new XProperty(X_OPENPAAS_BOOKING_LINK, bookingLinkPublicId.value().toString()));
        maybeMeetingLink.ifPresent(visioLink -> properties.add(
            new XProperty(X_OPENPAAS_VIDEOCONFERENCE, visioLink.toString()).add(Value.URI)));

        VEvent event = new VEvent(new PropertyList(properties.build()));
        options.alarm()
            .map(alarm -> buildAlarm(alarm, alarmRecipients(request, organizer, newExtraAttendees)))
            .ifPresent(event::add);

        ValidationResult validationResult = event.validate();
        if (validationResult.hasErrors()) {
            throw new IllegalStateException("Generated booking ICS is invalid for eventId " + eventUid.getValue() + ": " + validationResult);
        }
        return event;
    }

    /**
     * Every attendee of the event, deduplicated by email: {@code AlarmInstantFactory} reads the VALARM ATTENDEE
     * list to know who to notify.
     */
    private List<BookingAttendee> alarmRecipients(BookingRequest request,
                                                  BookingAttendee organizer,
                                                  List<BookingAttendee> extraAttendees) {
        return Stream.of(Stream.of(organizer, request.creator()),
                request.additionalAttendees().stream(),
                extraAttendees.stream())
            .flatMap(stream -> stream)
            .toList();
    }

    private VAlarm buildAlarm(BookingLinkAlarm alarm, List<BookingAttendee> recipients) {
        VAlarm vAlarm = new VAlarm();
        vAlarm.add(new Action(Action.VALUE_EMAIL));
        vAlarm.add(new Trigger(new ParameterList(List.of()), alarm.trigger()));
        recipients.forEach(recipient ->
            vAlarm.add(new Attendee(URI.create(MAIL_TO_PREFIX + recipient.email().asString()))));
        return vAlarm;
    }

    private Attendee buildResourceAttendee(BookingAttendee resource) {
        Attendee builtAttendee = (Attendee) new Attendee(URI.create(MAIL_TO_PREFIX + resource.email().asString()))
            .withParameter(Rsvp.TRUE)
            .withParameter(Role.REQ_PARTICIPANT)
            .withParameter(CuType.RESOURCE)
            .withParameter(PartStat.NEEDS_ACTION)
            .getFluentTarget();

        if (StringUtils.isNotBlank(resource.name())) {
            builtAttendee.withParameter(new Cn(resource.name()));
        }
        return builtAttendee;
    }

    private Optional<String> buildDescription(String notes, Optional<URL> maybeMeetingLink) {
        return Optional.ofNullable(StringUtils.trimToNull(Joiner.on('\n')
            .skipNulls()
            .join(StringUtils.trimToNull(notes), maybeMeetingLink
                .map(URL::toString)
                .map(VISIO_DESCRIPTION_PREFIX::concat)
                .orElse(null))));
    }

    /**
     * An extra attendee who booked the slot themselves, or who the booker also listed, already has an ATTENDEE
     * line: keep that one rather than emitting a duplicate with a conflicting PARTSTAT.
     */
    private List<BookingAttendee> newExtraAttendees(BookingRequest request, List<BookingAttendee> extraAttendees) {
        Set<String> alreadyInvited = Stream.concat(Stream.of(request.creator()), request.additionalAttendees().stream())
            .map(attendee -> attendee.email().asString())
            .collect(ImmutableSet.toImmutableSet());

        return extraAttendees.stream()
            .filter(extraAttendee -> !alreadyInvited.contains(extraAttendee.email().asString()))
            .toList();
    }

    private Attendee buildAttendee(BookingAttendee attendee) {
        return buildAttendee(attendee, PartStat.ACCEPTED);
    }

    /**
     * Extra attendees of the booking link were never asked: they are invited through the regular
     * iTIP flow and still have to answer.
     */
    private Attendee buildExtraAttendee(BookingAttendee attendee) {
        return buildAttendee(attendee, PartStat.NEEDS_ACTION);
    }

    private Attendee buildAttendee(BookingAttendee attendee, PartStat partStat) {
        Attendee builtAttendee = (Attendee) new Attendee(URI.create(MAIL_TO_PREFIX + attendee.email().asString()))
            .withParameter(Rsvp.TRUE)
            .withParameter(Role.REQ_PARTICIPANT)
            .withParameter(CuType.INDIVIDUAL)
            .withParameter(partStat)
            .getFluentTarget();

        if (StringUtils.isNotBlank(attendee.name())) {
            builtAttendee.withParameter(new Cn(attendee.name()));
        }
        return builtAttendee;
    }

    private List<Property> buildOrganizer(BookingAttendee attendee, boolean autoAccept) {
        Organizer organizer = new Organizer(URI.create(MAIL_TO_PREFIX + attendee.email().asString()));

        Attendee builtAttendee = (Attendee) new Attendee(URI.create(MAIL_TO_PREFIX + attendee.email().asString()))
            .withParameter(Rsvp.TRUE)
            .withParameter(Role.CHAIR)
            .withParameter(CuType.INDIVIDUAL)
            .withParameter(autoAccept ? PartStat.ACCEPTED : PartStat.NEEDS_ACTION)
            .getFluentTarget();

        if (StringUtils.isNotBlank(attendee.name())) {
            organizer.withParameter(new Cn(attendee.name()));
            builtAttendee.withParameter(new Cn(attendee.name()));
        }
        return List.of(organizer, builtAttendee);
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
