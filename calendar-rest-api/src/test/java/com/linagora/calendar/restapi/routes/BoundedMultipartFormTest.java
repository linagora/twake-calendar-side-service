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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;

class BoundedMultipartFormTest {
    private static final String BOUNDARY = "boundary";
    private static final long FILE_MAX_SIZE = 2 * 1024 * 1024;
    private static final long FIELD_MAX_SIZE = 16 * 1024;

    private final BoundedMultipartForm.PartBoundingDataFactory factory = new BoundedMultipartForm.PartBoundingDataFactory(FILE_MAX_SIZE, FIELD_MAX_SIZE);
    private final HttpPostMultipartRequestDecoder decoder = new HttpPostMultipartRequestDecoder(factory, multipartRequest());

    @AfterEach
    void tearDown() {
        decoder.destroy();
    }

    @Test
    void shouldRefuseAFormFieldPastItsLimitBeforeItsClosingBoundary() {
        decoder.offer(content("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\n"));

        assertThatThrownBy(() -> decoder.offer(content("A".repeat(64 * 1024))))
            .isInstanceOf(DecoderException.class);
        assertThat(decoder.currentPartialHttpData()).isInstanceOf(Attribute.class);
    }

    @Test
    void shouldAcceptAFilePartLargerThanTheFormFieldLimit() {
        decoder.offer(content("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"calendar.ics\"\r\n"
            + "Content-Type: text/calendar\r\n\r\n"));

        assertThatCode(() -> decoder.offer(content("A".repeat(64 * 1024))))
            .doesNotThrowAnyException();
    }

    @Test
    void shouldRefuseAFilePartPastItsLimitBeforeItsClosingBoundary() {
        decoder.offer(content("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"calendar.ics\"\r\n"
            + "Content-Type: text/calendar\r\n\r\n"));

        assertThatThrownBy(() -> decoder.offer(content("A".repeat((int) FILE_MAX_SIZE + 1024))))
            .isInstanceOf(DecoderException.class);
        assertThat(decoder.currentPartialHttpData()).isInstanceOf(FileUpload.class);
    }

    @Test
    void shouldRefuseACompleteFilePartPastItsLimit() {
        HttpPostMultipartRequestDecoder smallFileDecoder = new HttpPostMultipartRequestDecoder(
            new BoundedMultipartForm.PartBoundingDataFactory(5, FIELD_MAX_SIZE), multipartRequest());

        try {
            assertThatThrownBy(() -> smallFileDecoder.offer(content("--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"calendar.ics\"\r\n"
                + "Content-Type: text/calendar\r\n\r\n0123456789\r\n--" + BOUNDARY + "--\r\n")))
                .isInstanceOf(DecoderException.class);
            assertThat(smallFileDecoder.currentPartialHttpData()).isInstanceOf(FileUpload.class);
        } finally {
            smallFileDecoder.destroy();
        }
    }

    private static DefaultHttpRequest multipartRequest() {
        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/files");
        request.headers().set(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + BOUNDARY);
        return request;
    }

    private static HttpContent content(String value) {
        return new DefaultHttpContent(Unpooled.wrappedBuffer(value.getBytes(StandardCharsets.UTF_8)));
    }
}
