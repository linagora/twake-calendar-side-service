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

package com.linagora.calendar.dav;

import java.nio.charset.StandardCharsets;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.OpenPaaSId;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufMono;
import reactor.netty.http.client.HttpClientResponse;

public class CardDavClient extends DavClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(CardDavClient.class);

    private static final String CONTENT_TYPE_VCARD = "application/vcard";
    private static final String ACCEPT_VCARD_JSON = "text/plain";
    private static final String ADDRESS_BOOK_PATH = "/addressbooks/%s/%s/%s.vcf";

    protected CardDavClient(DavConfiguration config) throws SSLException {
        super(config);
    }

    private UnaryOperator<HttpHeaders> addHeaders(Username username) {
        return headers -> headers.add(HttpHeaderNames.ACCEPT, ACCEPT_VCARD_JSON)
            .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username.asString()));
    }

    public Mono<Void> createContact(Username username, OpenPaaSId userId, String addressBook, String vcardUid, byte[] vcardPayload) {
        return client.headers(headers -> addHeaders(username).apply(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD))
            .put()
            .uri(String.format(ADDRESS_BOOK_PATH, userId.value(), addressBook, vcardUid))
            .send(Mono.just(Unpooled.wrappedBuffer(vcardPayload)))
            .responseSingle((response, byteBufMono) -> handleContactCreationResponse(response, byteBufMono, userId, addressBook, vcardUid));
    }

    public Mono<byte[]> exportContact(Username username, OpenPaaSId userId, String addressBook) {
        return client.headers(headers -> addHeaders(username).apply(headers)
                .add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_VCARD))
            .request(HttpMethod.GET)
            .uri(String.format("/addressbooks/%s/%s?export", userId.value(), addressBook))
            .responseSingle((response, byteBufMono) -> handleContactExportResponse(response, byteBufMono, userId, addressBook));
    }

    private Mono<Void> handleContactCreationResponse(HttpClientResponse response, ByteBufMono responseContent, OpenPaaSId userId, String addressBook, String vcardUid) {
        return switch (response.status().code()) {
            case 201 -> Mono.empty();
            case 204 -> {
                LOGGER.info("Contact for user {} and addressBook {} and vcardUid {} already exists", userId.value(), addressBook, vcardUid);
                yield Mono.empty();
            }
            default -> responseContent.asString(StandardCharsets.UTF_8)
                .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                .flatMap(responseBody ->
                    Mono.error(new DavClientException("""
                                Unexpected status code: %d when creating contact for user %s and addressBook %s and vcardUid: %s
                                %s
                                """.formatted(response.status().code(), userId.value(), addressBook, vcardUid, responseBody))));
        };
    }

    private Mono<byte[]> handleContactExportResponse(HttpClientResponse response, ByteBufMono responseContent, OpenPaaSId userId, String addressBook) {
        if (response.status().code() == 200) {
            return responseContent.asByteArray();
        } else {
            return responseContent.asString(StandardCharsets.UTF_8)
                .switchIfEmpty(Mono.just(StringUtils.EMPTY))
                .flatMap(responseBody ->
                    Mono.error(new DavClientException("""
                                Unexpected status code: %d when exporting contact for user %s and addressBook %s
                                %s
                                """.formatted(response.status().code(), userId.value(), addressBook, responseBody))));
        }
    }
}
