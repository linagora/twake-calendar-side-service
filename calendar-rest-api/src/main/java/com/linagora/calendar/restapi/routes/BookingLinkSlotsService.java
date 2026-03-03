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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.james.core.Username;

import com.linagora.calendar.api.booking.AvailabilityRule.FixedAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.ComputeSlotsRequest;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.UnavailableTimeRanges.TimeRange;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import reactor.core.publisher.Mono;

public class BookingLinkSlotsService {
    private final BookingLinkDAO bookingLinkDAO;
    private final CalDavClient calDavClient;
    private final AvailableSlotsCalculator availableSlotsCalculator;

    @Inject
    public BookingLinkSlotsService(BookingLinkDAO bookingLinkDAO, CalDavClient calDavClient) {
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.availableSlotsCalculator = new AvailableSlotsCalculator.Default();
    }

    public Mono<Pair<BookingLink, Set<AvailabilitySlot>>> computeSlots(Username username, BookingLinkPublicId publicId, Instant from, Instant to) {
        return findActiveBookingLink(username, publicId)
            .flatMap(bookingLink -> computeSlots(bookingLink, from, to)
                .map(set -> Pair.of(bookingLink, set)));
    }

    private Mono<Set<AvailabilitySlot>> computeSlots(BookingLink bookingLink, Instant from, Instant to) {
        return retrieveUnavailableTimeRanges(bookingLink, from, to)
            .map(unavailableTimeRanges -> new ComputeSlotsRequest(
                bookingLink.duration(), from, to,
                resolveAvailabilityRules(bookingLink.availabilityRules(), from, to),
                unavailableTimeRanges))
            .map(availableSlotsCalculator::computeSlots);
    }

    private Mono<UnavailableTimeRanges> retrieveUnavailableTimeRanges(BookingLink bookingLink, Instant from, Instant to) {
        return calDavClient.findBusyIntervals(bookingLink.username(), bookingLink.calendarUrl(), from, to)
            .map(busyInterval -> new TimeRange(busyInterval.start(), busyInterval.end()))
            .collectList()
            .map(UnavailableTimeRanges::new);
    }

    private Mono<BookingLink> findActiveBookingLink(Username username, BookingLinkPublicId publicId) {
        return bookingLinkDAO.findByPublicId(username, publicId)
            .filter(BookingLink::active)
            .switchIfEmpty(Mono.error(() -> new BookingLinkNotFoundException(publicId)));
    }

    private AvailabilityRules resolveAvailabilityRules(Optional<AvailabilityRules> availabilityRules, Instant fromRequest, Instant toRequest) {
        return availabilityRules.orElseGet(() -> AvailabilityRules.of(
            new FixedAvailabilityRule(ZonedDateTime.ofInstant(fromRequest, ZoneOffset.UTC),
                ZonedDateTime.ofInstant(toRequest, ZoneOffset.UTC))));
    }
}
