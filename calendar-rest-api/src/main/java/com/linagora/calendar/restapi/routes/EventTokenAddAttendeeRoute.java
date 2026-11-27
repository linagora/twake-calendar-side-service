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

import static com.linagora.calendar.restapi.RestApiConstants.OBJECT_MAPPER_DEFAULT;

import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.jmap.Endpoint;
import org.apache.james.metrics.api.MetricFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Anyone holding the event token adds an attendee. The address being unverified, the attendee is added as needing
 * action, so that the owner of the address confirms by answering the invitation.
 */
public class EventTokenAddAttendeeRoute extends PublicRoute {

    public record AddAttendeeRequestDTO(@JsonProperty("mailAddress") String mailAddress,
                                        @JsonProperty("displayName") Optional<String> displayName) {

        MailAddress parsedMailAddress() {
            Preconditions.checkArgument(mailAddress != null, "'mailAddress' is required");
            try {
                return new MailAddress(mailAddress);
            } catch (AddressException e) {
                throw new IllegalArgumentException("Invalid 'mailAddress': " + mailAddress, e);
            }
        }

        Optional<String> normalizedDisplayName() {
            return Optional.ofNullable(displayName).flatMap(value -> value)
                .map(String::trim)
                .filter(value -> !value.isEmpty());
        }
    }

    private final EventTokenAttendees eventTokenAttendees;

    @Inject
    public EventTokenAddAttendeeRoute(MetricFactory metricFactory, EventTokenAttendees eventTokenAttendees) {
        super(metricFactory);
        this.eventTokenAttendees = eventTokenAttendees;
    }

    @Override
    protected Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/events/addAttendee");
    }

    @Override
    protected Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response) {
        return Mono.fromCallable(() -> EventTokenOwnership.eventTokenFromQuery(request))
            .flatMap(eventTokenId -> request.receive().aggregate().asByteArray()
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Request body must not be empty")))
                .map(this::parseRequest)
                .flatMap(dto -> eventTokenAttendees.add(eventTokenId, dto.parsedMailAddress(), dto.normalizedDisplayName(), PartStat.NEEDS_ACTION)))
            .then(response.status(HttpResponseStatus.NO_CONTENT).send());
    }

    private AddAttendeeRequestDTO parseRequest(byte[] body) {
        AddAttendeeRequestDTO dto;
        try {
            dto = OBJECT_MAPPER_DEFAULT.readValue(body, AddAttendeeRequestDTO.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid request body", e);
        }
        Preconditions.checkArgument(dto != null, "Invalid request body");
        return dto;
    }
}
