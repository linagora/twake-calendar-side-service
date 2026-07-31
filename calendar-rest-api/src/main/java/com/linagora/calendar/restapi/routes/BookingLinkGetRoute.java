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

import static com.linagora.calendar.restapi.RestApiConstants.JSON_HEADER;
import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;

import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.storage.booking.BookingLinkDAO;
import com.linagora.calendar.storage.booking.BookingLinkNotFoundException;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class BookingLinkGetRoute extends CalendarRoute {

    private static final String PUBLIC_ID_PARAM = "bookingLinkPublicId";

    private final BookingLinkDAO bookingLinkDAO;
    private final BookingLinkDTOFactory bookingLinkDTOFactory;

    @Inject
    public BookingLinkGetRoute(Authenticator authenticator,
                               MetricFactory metricFactory,
                               BookingLinkDAO bookingLinkDAO,
                               BookingLinkDTOFactory bookingLinkDTOFactory) {
        super(authenticator, metricFactory);
        this.bookingLinkDAO = bookingLinkDAO;
        this.bookingLinkDTOFactory = bookingLinkDTOFactory;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/api/booking-links/{" + PUBLIC_ID_PARAM + "}");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        BookingLinkPublicId publicId = new BookingLinkPublicId(UUID.fromString(request.param(PUBLIC_ID_PARAM)));

        return bookingLinkDAO.findByPublicId(session.getUser(), publicId)
            .switchIfEmpty(Mono.error(new BookingLinkNotFoundException(publicId)))
            .flatMap(bookingLinkDTOFactory::create)
            .flatMap(dto -> response.status(HttpResponseStatus.OK)
                .headers(JSON_HEADER)
                .sendByteArray(Mono.fromCallable(() -> OBJECT_MAPPER_DEFAULT.writeValueAsBytes(dto)))
                .then())
            .onErrorResume(BookingLinkNotFoundException.class, e ->
                response.status(HttpResponseStatus.NOT_FOUND).send().then());
    }
}
