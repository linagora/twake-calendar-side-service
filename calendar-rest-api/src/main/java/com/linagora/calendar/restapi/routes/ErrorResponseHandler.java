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

import com.linagora.calendar.restapi.ErrorResponse;
import com.linagora.calendar.restapi.ErrorType;
import com.linagora.calendar.restapi.RestApiConstants;

import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;

public class ErrorResponseHandler {

    public static Mono<Void> handle(HttpServerResponse response,
                                    HttpResponseStatus status,
                                    Throwable exception) {
        if (HttpResponseStatus.INTERNAL_SERVER_ERROR.equals(status)) {
            return handle(response, status, "Something went wrong.");
        }
        return handle(response, status, exception.getMessage());
    }

    public static Mono<Void> handle(HttpServerResponse response,
                                    HttpResponseStatus status,
                                    String message) {
        return handle(response, status, ErrorResponse.of(status.code(), status.reasonPhrase(), message));
    }

    public static Mono<Void> handle(HttpServerResponse response,
                                    HttpResponseStatus status,
                                    ErrorType type,
                                    Throwable exception) {
        return handle(response, status,
            ErrorResponse.of(status.code(), type.asString(), status.reasonPhrase(), exception.getMessage()));
    }

    public static Mono<Void> handle(HttpServerResponse response,
                                    HttpResponseStatus status,
                                    ErrorResponse errorResponse) {
        return response.status(status)
            .headers(RestApiConstants.JSON_HEADER)
            .sendByteArray(Mono.fromCallable(errorResponse::serializeAsBytes))
            .then();
    }
}
