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

package com.linagora.calendar.restapi.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import org.apache.james.core.Username;
import org.apache.james.mailbox.store.RandomMailboxSessionIdGenerator;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.user.api.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.storage.SimpleSessionProvider;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import reactor.netty.http.server.HttpServerRequest;

class BasicAuthenticationStrategyTest {
    private static final String ADMIN_USERNAME = "admin@domain.tld";
    private static final String ADMIN_PASSWORD = "?>?~";

    private BasicAuthenticationStrategy testee;

    @BeforeEach
    void setUp() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .adminUsername(Optional.of(ADMIN_USERNAME))
            .adminPassword(Optional.of(ADMIN_PASSWORD))
            .build();
        testee = new BasicAuthenticationStrategy(configuration, mock(UsersRepository.class),
            new SimpleSessionProvider(new RandomMailboxSessionIdGenerator()), new RecordingMetricFactory());
    }

    @Test
    void basicAuthShouldAcceptStandardBase64PlusAndSlash() {
        String base64 = encode(ADMIN_USERNAME + ":" + ADMIN_PASSWORD);
        assertThat(base64).containsAnyOf("+", "/");

        assertThat(testee.createMailboxSession(requestWithAuthorization("Basic " + base64)).block().getUser())
            .isEqualTo(Username.of(ADMIN_USERNAME));
    }

    @Test
    void basicAuthShouldRejectUrlSafeBase64Characters() {
        assertThatThrownBy(() -> testee.createMailboxSession(requestWithAuthorization("Basic YWJj-_#=")).block())
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static String encode(String credentials) {
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServerRequest requestWithAuthorization(String authorization) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.requestHeaders()).thenReturn(new DefaultHttpHeaders().add("Authorization", authorization));
        return request;
    }
}
