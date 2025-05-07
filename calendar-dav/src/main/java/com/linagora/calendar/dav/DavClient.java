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

import java.time.Duration;

import javax.net.ssl.SSLException;

import org.apache.http.auth.UsernamePasswordCredentials;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import reactor.netty.http.client.HttpClient;

public abstract class DavClient {
    protected static final Duration DEFAULT_RESPONSE_TIMEOUT = Duration.ofSeconds(10);

    protected final HttpClient client;
    protected final DavConfiguration config;

    protected DavClient(DavConfiguration config) throws SSLException {
        this.config = config;
        this.client = createHttpClient(config.trustAllSslCerts().orElse(false));
    }

    protected HttpClient createHttpClient(boolean trustAllSslCerts) throws SSLException {
        HttpClient client = HttpClient.create()
            .baseUrl(config.baseUrl().toString())
            .responseTimeout(config.responseTimeout().orElse(DEFAULT_RESPONSE_TIMEOUT));
        if (trustAllSslCerts) {
            SslContext sslContext = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            return client.secure(sslContextSpec -> sslContextSpec.sslContext(sslContext));
        }
        return client;
    }

    protected String authenticationToken(String username) {
        return HttpUtils.createBasicAuthenticationToken(new UsernamePasswordCredentials(
            config.adminCredential().getUserName() + "&" + username,
            config.adminCredential().getPassword()));
    }
}