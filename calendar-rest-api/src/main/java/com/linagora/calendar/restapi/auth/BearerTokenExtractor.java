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

import static org.apache.james.jmap.http.JWTAuthenticationStrategy.AUTHORIZATION_HEADER_PREFIX;

import java.util.Optional;

import org.apache.commons.lang3.Strings;

/** Extracts the token of a Bearer Authorization header. The auth-scheme is case-insensitive (RFC 9110 section 11.1)
 * and may be followed by several spaces (RFC 9110 section 11.4). */
public final class BearerTokenExtractor {

    public static Optional<String> extract(String authorizationHeader) {
        return Optional.ofNullable(authorizationHeader)
            .filter(header -> Strings.CI.startsWith(header, AUTHORIZATION_HEADER_PREFIX))
            .map(header -> header.substring(AUTHORIZATION_HEADER_PREFIX.length()).stripLeading());
    }

    private BearerTokenExtractor() {
    }
}
