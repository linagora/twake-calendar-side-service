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

package com.linagora.calendar.webadmin;

import java.util.Optional;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CalDavClient.PublicRight;

import spark.HaltException;
import spark.Request;

/**
 * Parses the {@code {"public_right": "{DAV:}read"}} bodies changing the public visibility of a calendar, be it
 * owned by a user, a team calendar or a resource.
 */
public class PublicRightParser {
    private static final String FIELD_PUBLIC_RIGHT = "public_right";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static PublicRight parse(Request request) {
        JsonNode publicRightNode = parseBody(request).path(FIELD_PUBLIC_RIGHT);
        if (!publicRightNode.isTextual()) {
            throw badRequest("Field '%s' is required".formatted(FIELD_PUBLIC_RIGHT));
        }
        return asPublicRight(publicRightNode.asText());
    }

    private static PublicRight asPublicRight(String publicRight) {
        return switch (publicRight) {
            case "" -> PublicRight.HIDE_ALL_EVENT;
            case "{DAV:}read" -> PublicRight.READ;
            default -> throw badRequest("Invalid '%s' value: '%s'. Supported values are: '' and '{DAV:}read'"
                .formatted(FIELD_PUBLIC_RIGHT, publicRight));
        };
    }

    private static JsonNode parseBody(Request request) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(request.bodyAsBytes());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Request body must be a JSON object");
            }
            return body;
        } catch (Exception e) {
            String detail = Optional.ofNullable(ExceptionUtils.getRootCause(e))
                .map(Throwable::getMessage)
                .orElse(e.getMessage());
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request body: %s".formatted(detail))
                .cause(e)
                .haltError();
        }
    }

    private static HaltException badRequest(String message) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message(message)
            .haltError();
    }

    private PublicRightParser() {
    }
}
