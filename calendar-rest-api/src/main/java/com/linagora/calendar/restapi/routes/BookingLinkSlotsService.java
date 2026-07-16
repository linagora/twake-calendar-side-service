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
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

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
import com.linagora.calendar.dav.FreeBusyQueryResponseObject.BusyInterval;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotActiveException;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class BookingLinkSlotsService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkSlotsService.class);

    public record SlotsResult(BookingLink bookingLink, OpenPaaSUser owner, Set<AvailabilitySlot> slots) {
    }

    private final Clock clock;
    private final BookingLinkDAO bookingLinkDAO;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final AvailableSlotsCalculator availableSlotsCalculator;

    @Inject
    public BookingLinkSlotsService(Clock clock, BookingLinkDAO bookingLinkDAO, OpenPaaSUserDAO openPaaSUserDAO, CalDavClient calDavClient) {
        this.clock = clock;
        this.bookingLinkDAO = bookingLinkDAO;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.availableSlotsCalculator = new AvailableSlotsCalculator.Default();
    }

    public Mono<SlotsResult> computeSlots(BookingLinkPublicId publicId, Instant from, Instant to) {
        return getBookingLink(publicId)
            .flatMap(bookingLink -> Mono.zip(
                    retrieveOwner(bookingLink),
                    computeSlots(bookingLink, from, to))
                .map(tuple -> new SlotsResult(bookingLink, tuple.getT1(), tuple.getT2())));
    }

    Mono<Set<AvailabilitySlot>> computeSlots(BookingLink bookingLink, Instant from, Instant to) {
        return retrieveUnavailableTimeRanges(bookingLink, from, to)
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

    private Mono<UnavailableTimeRanges> retrieveUnavailableTimeRanges(BookingLink bookingLink, Instant from, Instant to) {
        return Flux.concat(
                calDavClient.findBusyIntervals(bookingLink.username(), bookingLink.calendarUrl(), from, to),
                extraAttendeesBusyIntervals(bookingLink, from, to))
            .map(busyInterval -> new TimeRange(busyInterval.start(), busyInterval.end()))
            .collectList()
            .map(UnavailableTimeRanges::new);
    }

    /**
     * Extra attendees availability is seen from the organizer point of view: the free-busy query runs as the
     * booking link owner, so the calendar server decides what the owner may see of each attendee calendar.
     * A calendar that cannot be read must not break the whole booking link: such an attendee is treated as free.
     * Busy intervals are collected per attendee so that a failure mid-answer discards that attendee's partial
     * view rather than mixing it in.
     */
    private Flux<BusyInterval> extraAttendeesBusyIntervals(BookingLink bookingLink, Instant from, Instant to) {
        return Flux.fromIterable(bookingLink.extraAttendees())
            .concatMap(extraAttendee -> calDavClient.findBusyIntervals(bookingLink.username(), CalendarURL.from(extraAttendee), from, to)
                .collectList()
                .onErrorResume(error -> {
                    LOGGER.warn("Booking link {} could not read the calendar of extra attendee {} as {}: treating them as free",
                        bookingLink.publicId().value(), extraAttendee.value(), bookingLink.username().asString(), error);
                    return Mono.just(List.of());
                })
                .flatMapIterable(busyIntervals -> busyIntervals));
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
