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

package com.linagora.calendar.api;

import java.security.PublicKey;
import java.util.List;
import java.util.Optional;

import org.apache.james.jwt.DefaultPublicKeyProvider;
import org.apache.james.jwt.JwtConfiguration;
import org.apache.james.jwt.PublicKeyProvider;
import org.apache.james.jwt.PublicKeyReader;

import com.google.common.collect.ImmutableList;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;

public class JwtVerifier {

    public static JwtVerifier create(JwtConfiguration jwtConfiguration) {
        PublicKeyProvider publicKeyProvider = new DefaultPublicKeyProvider(jwtConfiguration, new PublicKeyReader());
        return new JwtVerifier(publicKeyProvider);
    }

    private final List<JwtParser> jwtParsers;

    public JwtVerifier(PublicKeyProvider pubKeyProvider) {
        this.jwtParsers = pubKeyProvider.get()
            .stream()
            .map(this::toImmutableJwtParser)
            .collect(ImmutableList.toImmutableList());
    }

    private JwtParser toImmutableJwtParser(PublicKey publicKey) {
        return Jwts.parser()
            .verifyWith(publicKey)
            .build();
    }

    public Claims verify(String jwt) {
        return jwtParsers.stream()
            .flatMap(jwtParser -> verifyWithParsers(jwtParser, jwt).stream())
            .findFirst()
            .orElseThrow(() -> new JwtException("Invalid JWT token " + jwt));
    }

    private Optional<Claims> verifyWithParsers(JwtParser jwtParser, String jwt) {
        try {
            return Optional.of(jwtParser.parseSignedClaims(jwt).getPayload());
        } catch (Exception e) {
            // Ignore and try next parser
        }
        return Optional.empty();
    }
}
