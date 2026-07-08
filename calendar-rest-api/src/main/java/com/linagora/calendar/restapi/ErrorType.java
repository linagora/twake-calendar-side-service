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

package com.linagora.calendar.restapi;

import io.netty.handler.codec.http.HttpResponseStatus;

public enum ErrorType {
    BAD_REQUEST("BadRequest"),
    UNAUTHORIZED("Unauthorized"),
    FORBIDDEN("Forbidden"),
    NOT_FOUND("NotFound"),
    UNPROCESSABLE_ENTITY("UnprocessableEntity"),
    SERVICE_UNAVAILABLE("ServiceUnavailable"),
    SERVER_ERROR("ServerError"),
    INACTIVE_BOOKING_LINK("InactiveBookingLink");

    public static ErrorType fromStatus(HttpResponseStatus status) {
        return switch (status.code()) {
            case 400 -> BAD_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 422 -> UNPROCESSABLE_ENTITY;
            case 503 -> SERVICE_UNAVAILABLE;
            default -> SERVER_ERROR;
        };
    }

    private final String value;

    ErrorType(String value) {
        this.value = value;
    }

    public String asString() {
        return value;
    }
}
