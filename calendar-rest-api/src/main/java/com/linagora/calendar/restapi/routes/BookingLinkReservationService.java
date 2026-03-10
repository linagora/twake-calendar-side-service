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
import java.util.List;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import reactor.core.publisher.Mono;

public class BookingLinkReservationService {

    private final BookingLinkDAO bookingLinkDAO;
    private final BookingLinkSlotsService bookingLinkSlotsService;
    private final BookingLinkEventIcsBuilder bookingLinkEventIcsBuilder;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public BookingLinkReservationService(BookingLinkDAO bookingLinkDAO,
                                         Clock clock,
                                         BookingLinkSlotsService bookingLinkSlotsService,
                                         RestApiConfiguration restApiConfiguration,
                                         CalDavClient calDavClient,
                                         OpenPaaSUserDAO openPaaSUserDAO) {
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.bookingLinkSlotsService = bookingLinkSlotsService;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.bookingLinkEventIcsBuilder = new BookingLinkEventIcsBuilder(clock, new MeetingConferenceLinkResolver.Default(restApiConfiguration));

    }

    public Mono<Void> book(BookingLinkPublicId publicId, BookingRequest request) {
        return bookingLinkDAO.findActiveByPublicId(publicId)
            .switchIfEmpty(Mono.error(() -> new BookingLinkNotFoundException(publicId)))
            .flatMap(bookingLink -> validateSlotAvailability(bookingLink, request.slotStartUtc())
                .then(createBooking(bookingLink, request)));
    }

    private Mono<Void> validateSlotAvailability(BookingLink bookingLink, Instant startUtc) {
        Instant queryStart = startUtc.minus(bookingLink.duration());
        Instant queryEnd = startUtc.plus(bookingLink.duration());

        return bookingLinkSlotsService.computeSlots(bookingLink, queryStart, queryEnd)
            .onErrorMap(throwable -> BookingLinkReservationException.computationFailed(bookingLink.publicId(), throwable))
            .filter(availabilitySlots -> availabilitySlots.contains(new AvailabilitySlot(startUtc, bookingLink.duration())))
            .switchIfEmpty(Mono.error(() -> BookingLinkReservationException.notAvailable(bookingLink.publicId(), startUtc)))
            .then();
    }

    private Mono<Void> createBooking(BookingLink bookingLink, BookingRequest request) {
        return openPaaSUserDAO.retrieve(bookingLink.username())
            .map(owner -> BookingAttendee.from(owner.fullName(), owner.username().asString()))
            .map(organizer -> bookingLinkEventIcsBuilder.build(request, organizer, bookingLink.duration()))
            .flatMap(eventIcsResult -> calDavClient.importCalendar(bookingLink.calendarUrl(), eventIcsResult.eventIdAsString(), bookingLink.username(), eventIcsResult.icsBytes())
                .onErrorMap(throwable -> BookingLinkReservationException.createEventFailed(bookingLink.publicId(), eventIcsResult.eventIdAsString(), throwable)).then());
    }

    public record BookingRequest(Instant slotStartUtc,
                                 BookingAttendee creator,
                                 List<BookingAttendee> additionalAttendees,
                                 String title,
                                 boolean visioLink,
                                 String notes) {
        public record BookingAttendee(String name, MailAddress email) {
            public static BookingAttendee from(String name, String email) {
                Preconditions.checkArgument(StringUtils.isNotBlank(email), "'email' must not be blank");
                try {
                    return new BookingAttendee(name, new MailAddress(email));
                } catch (AddressException e) {
                    throw new IllegalArgumentException("'email' has invalid format: " + email, e);
                }
            }
        }

        public BookingRequest {
            Preconditions.checkNotNull(slotStartUtc, "'slotStartUtc' must not be null");
            Preconditions.checkNotNull(creator, "'creator' must not be null");
            Preconditions.checkNotNull(additionalAttendees, "'additionalAttendees' must not be null");
            Preconditions.checkArgument(StringUtils.isNotBlank(title), "'eventTitle' must not be blank");
        }
    }

}
