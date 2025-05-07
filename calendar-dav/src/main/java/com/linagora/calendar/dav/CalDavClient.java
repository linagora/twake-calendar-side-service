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

import org.apache.http.HttpStatus;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;

import com.linagora.calendar.storage.CalendarURL;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import reactor.core.publisher.Mono;

public class CalDavClient extends DavClient {

    public static class CalDavExportException extends DavClientException {
        public CalDavExportException(CalendarURL calendarUrl, Username username, String davResponse) {
            super("Failed to export calendar. URL: " + calendarUrl.asUri() + ", User: " + username.asString() +
                "\nDav Response: " + davResponse);
        }
    }

    private static final String ACCEPT_XML = "application/xml";

    protected CalDavClient(DavConfiguration config) throws SSLException {
        super(config);
    }

    private UnaryOperator<HttpHeaders> addHeaders(String username) {
        return headers -> headers.add(HttpHeaderNames.ACCEPT, ACCEPT_XML)
            .add(HttpHeaderNames.AUTHORIZATION, authenticationToken(username));
    }

    public Mono<byte[]> export(CalendarURL calendarURL, MailboxSession session) {
        return client.headers(headers -> addHeaders(session.getUser().asString()).apply(headers))
            .request(HttpMethod.GET)
            .uri(calendarURL.asUri() + "?export")
            .responseSingle((response, byteBufMono) -> {
                if (response.status().code() == HttpStatus.SC_OK) {
                    return byteBufMono.asByteArray();
                } else {
                    return byteBufMono
                        .asString(StandardCharsets.UTF_8)
                        .flatMap(errorBody -> Mono.error(new CalDavExportException(calendarURL, session.getUser(), "Response status: " + response.status().code() + " - " + errorBody)));
                }
            });
    }

}
