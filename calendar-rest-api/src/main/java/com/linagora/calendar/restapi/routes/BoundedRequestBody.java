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

import java.io.ByteArrayOutputStream;
import java.util.Optional;

import io.netty.handler.codec.http.HttpHeaderNames;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

/**
 * Reads a request body into memory, refusing it as soon as it exceeds a maximum size.
 */
public record BoundedRequestBody(long maxBytes) {

    public static class PayloadTooLargeException extends RuntimeException {
        public PayloadTooLargeException(long maxBytes) {
            super("Request body must not exceed " + maxBytes + " bytes");
        }
    }

    public Mono<byte[]> read(HttpServerRequest request) {
        return Mono.defer(() -> {
            // Refuse a declared oversized body before reading any of it.
            Optional.ofNullable(request.requestHeaders().get(HttpHeaderNames.CONTENT_LENGTH))
                .map(Long::parseLong)
                .ifPresent(this::validateSize);
            // Count received bytes as well: chunked requests have no Content-Length.
            return request.receive().asByteArray()
                .reduceWith(ByteArrayOutputStream::new, this::append)
                .map(ByteArrayOutputStream::toByteArray);
        });
    }

    private ByteArrayOutputStream append(ByteArrayOutputStream body, byte[] chunk) {
        validateSize((long) body.size() + chunk.length);
        body.writeBytes(chunk);
        return body;
    }

    private void validateSize(long size) {
        if (size > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
    }
}
