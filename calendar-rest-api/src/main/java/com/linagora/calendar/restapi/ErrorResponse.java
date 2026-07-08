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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

public record ErrorResponse(@JsonProperty("error") ErrorDetail error) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new Jdk8Module());

    public static ErrorResponse of(int code, ErrorType type, String message, String details) {
        return of(code, type.asString(), message, details);
    }

    public static ErrorResponse of(int code, String type, String message, String details) {
        return new ErrorResponse(new ErrorDetail(code, type, message, details));
    }

    public static ErrorResponse of(IllegalArgumentException illegalArgumentException) {
        return of(400, ErrorType.BAD_REQUEST, "Bad request", illegalArgumentException.getMessage());
    }

    public record ErrorDetail(@JsonProperty("code") int code,
                              @JsonProperty("type") String type,
                              @JsonProperty("message") String message,
                              @JsonProperty("details") String details) {
    }

    public byte[] serializeAsBytes() {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ErrorResponse to JSON", e);
        }
    }
}