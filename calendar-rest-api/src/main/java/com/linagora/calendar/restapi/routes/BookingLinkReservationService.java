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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.booking.AvailableSlotsCalculator.AvailabilitySlot;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.routes.BookingLinkEventIcsBuilder.BuildResult;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import reactor.core.publisher.Mono;

public class BookingLinkReservationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkReservationService.class);

    private final BookingLinkDAO bookingLinkDAO;
    private final BookingLinkSlotsService bookingLinkSlotsService;
    private final BookingLinkEventIcsBuilder bookingLinkEventIcsBuilder;
    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final PublicAgendaProposalNotifier publicAgendaProposalNotifier;

    @Inject
    public BookingLinkReservationService(BookingLinkDAO bookingLinkDAO,
                                         Clock clock,
                                         BookingLinkSlotsService bookingLinkSlotsService,
                                         RestApiConfiguration restApiConfiguration,
                                         CalDavClient calDavClient,
                                         OpenPaaSUserDAO openPaaSUserDAO,
                                         PublicAgendaProposalNotifier publicAgendaProposalNotifier) {
        this.bookingLinkDAO = bookingLinkDAO;
        this.calDavClient = calDavClient;
        this.bookingLinkSlotsService = bookingLinkSlotsService;
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.publicAgendaProposalNotifier = publicAgendaProposalNotifier;
        this.bookingLinkEventIcsBuilder = new BookingLinkEventIcsBuilder(clock, new MeetingConferenceLinkResolver.Visio(restApiConfiguration));

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
            .flatMap(organizer -> {
                BuildResult eventIcsResult = bookingLinkEventIcsBuilder.build(request,
                    BookingAttendee.from(organizer.fullName(), organizer.username().asString()), bookingLink.duration());

                return calDavClient.importCalendar(bookingLink.calendarUrl(), eventIcsResult.eventIdAsString(), bookingLink.username(), eventIcsResult.icsBytes())
                    .onErrorMap(throwable -> BookingLinkReservationException.createEventFailed(bookingLink.publicId(), eventIcsResult.eventIdAsString(), throwable))
                    .then(notifyBookingCreated(new BookingCreated(bookingLink, request, organizer, eventIcsResult)));
            })
            .then();
    }

    private Mono<Void> notifyBookingCreated(BookingCreated bookingCreated) {
        return publicAgendaProposalNotifier.notify(bookingCreated)
            .onErrorResume(error -> {
                LOGGER.warn("Failed to send proposal notification for booking {}: {}",
                    bookingCreated.eventIcsResult().eventIdAsString(), error.getMessage(), error);
                return Mono.empty();
            });
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

    public record BookingCreated(BookingLink bookingLink,
                                 BookingRequest request,
                                 OpenPaaSUser organizer,
                                 BuildResult eventIcsResult) {
    }

}
