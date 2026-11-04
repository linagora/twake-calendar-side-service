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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BearerTokenExtractorTest {
    private static final String TOKEN = "eyJhbGciOiJSUzI1NiJ9.eyJ0d2FrZV9jYWxlbmRhcl9hdXRoX3R5cGUiOiJqd3QifQ.c2ln";

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer  ", "bearer   ", "BEARER    "})
    void extractShouldIgnoreTheSpacesAfterTheScheme(String prefix) {
        assertThat(BearerTokenExtractor.extract(prefix + TOKEN)).contains(TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer ", "Bearer  "})
    void aServiceJwtShouldBeHandledByTheJwtStrategy(String prefix) {
        String token = BearerTokenExtractor.extract(prefix + TOKEN).orElseThrow();

        assertThat(JwtAuthenticationStrategy.isServiceJwt(token)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {" " + TOKEN, "opaque-oidc-token"})
    void tokensFailingTheJwtHeuristicShouldNotBeServiceJwt(String token) {
        assertThat(JwtAuthenticationStrategy.isServiceJwt(token)).isFalse();
    }
}
