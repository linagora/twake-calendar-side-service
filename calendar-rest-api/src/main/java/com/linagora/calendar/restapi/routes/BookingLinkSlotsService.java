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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.ComputeSlotsRequest;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges.TimeRange;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarSearchSourceResolver;
import com.linagora.calendar.dav.dto.FreeBusyBulkResponse.BusyInterval;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotActiveException;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class BookingLinkSlotsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkSlotsService.class);

    public record SlotsResult(BookingLink bookingLink, OpenPaaSUser owner, Set<AvailabilitySlot> slots,
                              List<Resource> resources, List<OpenPaaSUser> extraAttendees) {
    }

    private final Clock clock;
    private final BookingLinkDAO bookingLinkDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final BookingLinkResourceResolver resourceResolver;
    private final BookingLinkExtraAttendeeResolver extraAttendeeResolver;
    private final CalendarSearchSourceResolver calendarSourceResolver;
    private final SettingsBasedResolver settingsResolver;
    private final AvailableSlotsCalculator availableSlotsCalculator;

    @Inject
    public BookingLinkSlotsService(Clock clock, BookingLinkDAO bookingLinkDAO, OpenPaaSUserDAO openPaaSUserDAO,
                                   CalDavClient calDavClient, BookingLinkResourceResolver resourceResolver,
                                   BookingLinkExtraAttendeeResolver extraAttendeeResolver,
                                   CalendarSearchSourceResolver calendarSourceResolver,
                                   @Named("language_timezone") SettingsBasedResolver settingsResolver) {
        this.clock = clock;
        this.bookingLinkDAO = bookingLinkDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.resourceResolver = resourceResolver;
        this.extraAttendeeResolver = extraAttendeeResolver;
        this.calendarSourceResolver = calendarSourceResolver;
        this.settingsResolver = settingsResolver;
        this.availableSlotsCalculator = new AvailableSlotsCalculator.Default();
    }

    public Mono<SlotsResult> computeSlots(BookingLinkPublicId publicId, Instant from, Instant to) {
        return getBookingLink(publicId)
            .flatMap(bookingLink -> retrieveOwner(bookingLink)
                .flatMap(owner -> Mono.zip(
                        computeSlots(bookingLink, owner, from, to),
                        resolveResources(bookingLink),
                        resolveExtraAttendees(bookingLink))
                    .map(tuple -> new SlotsResult(bookingLink, owner, tuple.getT1(), tuple.getT2(), tuple.getT3()))));
    }

    private Mono<List<Resource>> resolveResources(BookingLink bookingLink) {
        return resourceResolver.resolveNames(bookingLink.resources())
            .collectList();
    }

    private Mono<List<OpenPaaSUser>> resolveExtraAttendees(BookingLink bookingLink) {
        return extraAttendeeResolver.resolveExisting(bookingLink.extraAttendees().participants())
            .collectList();
    }

    Mono<Set<AvailabilitySlot>> computeSlots(BookingLink bookingLink, Instant from, Instant to) {
        return retrieveOwner(bookingLink)
            .flatMap(owner -> computeSlots(bookingLink, owner, from, to));
    }

    private Mono<Set<AvailabilitySlot>> computeSlots(BookingLink bookingLink, OpenPaaSUser owner, Instant from, Instant to) {
        return retrieveUnavailableTimeRanges(bookingLink, owner, from, to)
            .map(unavailableTimeRanges -> toComputeSlotsRequest(bookingLink, from, to, unavailableTimeRanges))
            .map(availableSlotsCalculator::computeSlots)
            .map(this::filterOutPastSlots);
    }

    private Set<AvailabilitySlot> filterOutPastSlots(Set<AvailabilitySlot> slots) {
        // Prevent time travel: slots starting in the past must never be offered for booking.
        Instant now = clock.instant();
        return slots.stream()
            .filter(slot -> !slot.start().isBefore(now))
            .collect(Collectors.toUnmodifiableSet());
    }

    private ComputeSlotsRequest toComputeSlotsRequest(BookingLink bookingLink,
                                                      Instant from,
                                                      Instant to,
                                                      UnavailableTimeRanges unavailableTimeRanges) {
        AvailabilityRules availabilityRules = bookingLink.availabilityRules()
            .orElseGet(() -> AvailabilityRules.of(
                new FixedAvailabilityRule(ZonedDateTime.ofInstant(from, ZoneOffset.UTC),
                    ZonedDateTime.ofInstant(to, ZoneOffset.UTC))));

        return new ComputeSlotsRequest(bookingLink.duration(), from, to, availabilityRules, unavailableTimeRanges);
    }

    /**
     * Availability is that of the calendar home the booking link writes into, not of a single calendar:
     * querying only the target calendar would ignore the events living in the other calendars of that home.
     * That home is the owner's for a personal booking link, and the team calendar for a link pointing at one,
     * whose availability is the team's rather than its author's.
     * All-day events are days of the owner's time zone: read in UTC, a day off would start at 02:00 for a Paris
     * owner in summer, leaving the first hours of that day open to booking.
     */
    private Mono<UnavailableTimeRanges> retrieveUnavailableTimeRanges(BookingLink bookingLink, OpenPaaSUser owner, Instant from, Instant to) {
        return Mono.zip(availabilityHome(bookingLink, owner), zoneId(owner))
            .flatMapMany(tuple -> Flux.concat(
                calDavClient.findBusyIntervals(bookingLink.username(), tuple.getT1(), from, to, tuple.getT2()),
                extraAttendeesBusyIntervals(bookingLink, from, to, tuple.getT2())))
            .map(busyInterval -> new TimeRange(busyInterval.start(), busyInterval.end()))
            .collectList()
            .map(UnavailableTimeRanges::new);
    }

    /**
     * The calendar home whose free/busy makes the booking link busy. A calendar the owner writes into is not
     * always theirs: a team calendar, or a calendar delegated to them, appears in their home as an instance of
     * a calendar living in somebody else's home. What such a booking link must offer is the availability of
     * that source home - the team's rather than the team member's.
     */
    private Mono<OpenPaaSId> availabilityHome(BookingLink bookingLink, OpenPaaSUser owner) {
        CalendarURL calendarUrl = bookingLink.calendarUrl();

        return calendarSourceResolver.resolve(owner, List.of(calendarUrl))
            .map(sourceByCalendarUrl -> sourceByCalendarUrl.getOrDefault(calendarUrl, calendarUrl).base())
            .onErrorResume(error -> {
                LOGGER.warn("Booking link {} could not resolve the source of calendar {}: falling back to that calendar home",
                    bookingLink.publicId().value(), calendarUrl.asUri(), error);
                return Mono.just(calendarUrl.base());
            });
    }

    private Mono<ZoneId> zoneId(OpenPaaSUser user) {
        return settingsResolver.resolveOrDefault(user.username())
            .map(SettingsBasedResolver.ResolvedSettings::zoneId);
    }

    /**
     * Extra attendees availability is seen from the organizer point of view: the free/busy query runs as the
     * booking link owner, so the calendar server decides what the owner may see of each attendee calendar.
     * An attendee whose calendars cannot be read at all must not break the whole booking link: they are
     * treated as free. Busy intervals are collected per attendee so that a failure mid-answer discards that
     * attendee's partial view rather than mixing it in. The all-day events of an attendee are days of their own
     * time zone, the owner's one standing in when theirs cannot be resolved.
     */
    private Flux<BusyInterval> extraAttendeesBusyIntervals(BookingLink bookingLink, Instant from, Instant to, ZoneId ownerZoneId) {
        return Flux.fromIterable(bookingLink.extraAttendees().participants())
            .concatMap(extraAttendee -> extraAttendeeZoneId(extraAttendee, ownerZoneId)
                .flatMapMany(zoneId -> calDavClient.findBusyIntervals(bookingLink.username(), extraAttendee, from, to, zoneId))
                .collectList()
                .onErrorResume(error -> {
                    LOGGER.warn("Booking link {} could not read the calendars of extra attendee {} as {}: treating them as free",
                        bookingLink.publicId().value(), extraAttendee.value(), bookingLink.username().asString(), error);
                    return Mono.just(List.of());
                })
                .flatMapIterable(busyIntervals -> busyIntervals));
    }

    private Mono<ZoneId> extraAttendeeZoneId(OpenPaaSId extraAttendee, ZoneId ownerZoneId) {
        return openPaaSUserDAO.retrieve(extraAttendee)
            .flatMap(this::zoneId)
            .defaultIfEmpty(ownerZoneId);
    }

    Mono<BookingLink> getBookingLink(BookingLinkPublicId publicId) {
        return bookingLinkDAO.findByPublicId(publicId)
            .switchIfEmpty(Mono.error(() -> new BookingLinkNotFoundException(publicId)))
            .handle((bookingLink, sink) -> {
                if (!bookingLink.active()) {
                    sink.error(new BookingLinkNotActiveException(publicId));
                    return;
                }
                sink.next(bookingLink);
            });
    }

    private Mono<OpenPaaSUser> retrieveOwner(BookingLink bookingLink) {
        return openPaaSUserDAO.retrieve(bookingLink.username())
            // Public booking links whose owner disappeared should behave as unavailable.
            .switchIfEmpty(Mono.error(() -> new BookingLinkNotFoundException(bookingLink.publicId())));
    }

}
