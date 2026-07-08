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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import io.netty.handler.codec.http.HttpResponseStatus;

public class ErrorResponseTest {

    @Test
    void serializeShouldAlwaysExposeTypeField() {
        String json = new String(ErrorResponse.of(404, ErrorType.NOT_FOUND, "Not found", "Token not found or expired")
            .serializeAsBytes(), StandardCharsets.UTF_8);

        assertThatJson(json).isEqualTo("""
            {
                "error": {
                    "code": 404,
                    "type": "NotFound",
                    "message": "Not found",
                    "details": "Token not found or expired"
                }
            }""");
    }

    @Test
    void ofIllegalArgumentExceptionShouldExposeBadRequestType() {
        String json = new String(ErrorResponse.of(new IllegalArgumentException("boom"))
            .serializeAsBytes(), StandardCharsets.UTF_8);

        assertThatJson(json).isEqualTo("""
            {
                "error": {
                    "code": 400,
                    "type": "BadRequest",
                    "message": "Bad request",
                    "details": "boom"
                }
            }""");
    }

    @Test
    void fromStatusShouldFallBackToServerErrorForUnmappedStatus() {
        String json = new String(ErrorResponse.of(500, ErrorType.fromStatus(HttpResponseStatus.INTERNAL_SERVER_ERROR),
                "Internal Server Error", "boom")
            .serializeAsBytes(), StandardCharsets.UTF_8);

        assertThatJson(json).isEqualTo("""
            {
                "error": {
                    "code": 500,
                    "type": "ServerError",
                    "message": "Internal Server Error",
                    "details": "boom"
                }
            }""");
    }
}
