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

import jakarta.inject.Inject;

import com.linagora.calendar.restapi.routes.dto.BookingLinkDTO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.booking.BookingLink;
import com.linagora.calendar.storage.model.Resource;

import reactor.core.publisher.Mono;

/**
 * Builds a {@link BookingLinkDTO} from a {@link BookingLink}, resolving the display names of its extra attendees
 * and resources so the response carries names alongside ids.
 */
public class BookingLinkDTOFactory {

    private final BookingLinkExtraAttendeeResolver extraAttendeeResolver;
    private final BookingLinkResourceResolver resourceResolver;

    @Inject
    public BookingLinkDTOFactory(BookingLinkExtraAttendeeResolver extraAttendeeResolver,
                                 BookingLinkResourceResolver resourceResolver) {
        this.extraAttendeeResolver = extraAttendeeResolver;
        this.resourceResolver = resourceResolver;
    }

    public Mono<BookingLinkDTO> create(BookingLink bookingLink) {
        return Mono.zip(
                extraAttendeeResolver.resolveExisting(bookingLink.extraAttendees().participants())
                    .collectMap(OpenPaaSUser::id, user -> user),
                resourceResolver.resolveNames(bookingLink.resources())
                    .collectMap(Resource::id, Resource::name))
            .map(tuple -> BookingLinkDTO.from(bookingLink, tuple.getT1(), tuple.getT2()));
    }
}
