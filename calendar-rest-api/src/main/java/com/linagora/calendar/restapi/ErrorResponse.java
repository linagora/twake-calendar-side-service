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

public record ErrorResponse(@JsonProperty("error") ErrorDetail error) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static ErrorResponse of(int code, String message, String details) {
        return new ErrorResponse(new ErrorDetail(code, message, details));
    }

    public static ErrorResponse of(IllegalArgumentException illegalArgumentException) {
        return new ErrorResponse(new ErrorDetail(400, "Bad request", illegalArgumentException.getMessage()));
    }

    public record ErrorDetail(@JsonProperty("code") int code,
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