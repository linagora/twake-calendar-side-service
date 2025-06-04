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

package com.linagora.calendar.storage;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import com.linagora.calendar.storage.TechnicalTokenService.JwtToken;
import com.linagora.calendar.storage.TechnicalTokenService.TechnicalTokenClaimException;
import com.linagora.calendar.storage.TechnicalTokenService.TechnicalTokenInfo;

public class TechnicalTokenServiceTest {

    private TechnicalTokenService testee;

    @BeforeEach
    void setUp() {
        String secret = "super-secret-key";
        Duration expiration = Duration.ofHours(1);
        testee = new TechnicalTokenService.Impl(secret, expiration);
    }

    @Test
    void generateThenClaimShouldReturnExpectedInfo() {
        OpenPaaSId domainId = new OpenPaaSId("test-domain-id");
        JwtToken jwtToken = testee.generate(domainId).block();

        TechnicalTokenInfo technicalTokenInfo = testee.claim(jwtToken).block();

        assertThat(technicalTokenInfo)
            .isEqualTo(new TechnicalTokenInfo(
                domainId,
                Map.of("__v", 0,
                    "data", Map.of("principal", "principals/technicalUser"),
                    "schemaVersion", 1,
                    "description", "Allows to authenticate on Sabre DAV",
                    "name", "Sabre Dav",
                    "user_type", "technical",
                    "domainId", "test-domain-id",
                    "_id", "4890c190-d1d6-392e-afbf-991d4ce359b6",
                    "type", "dav")
            ));
    }

    @Test
    void generateShouldReturnJwtToken() {
        OpenPaaSId domainId = new OpenPaaSId("test-domain-id");
        JwtToken jwtToken = testee.generate(domainId).block();

        assertThat(jwtToken.value())
            .startsWith("eyJ"); // Heuristic for detecting JWT

        assertThatJson(decodeJWTPayloadPart(jwtToken.value()))
            .whenIgnoringPaths("exp")
            .isEqualTo("""
                {
                    "iss": "Twake-calendar-side-service",
                    "domainId": "test-domain-id",
                    "technicalToken": true,
                    "data": {
                        "description": "Allows to authenticate on Sabre DAV",
                        "name": "Sabre Dav",
                        "user_type": "technical",
                        "type": "dav",
                        "__v": 0,
                        "data": {
                            "principal": "principals/technicalUser"
                        },
                        "schemaVersion": 1,
                        "domainId": "test-domain-id",
                        "_id": "4890c190-d1d6-392e-afbf-991d4ce359b6"
                    },
                    "exp": 4070908920
                }
                """);
    }

    @Test
    void generateShouldProduceDifferentPayloadsForDifferentDomains() throws JsonProcessingException {
        JwtToken jwtToken1 = testee.generate(new OpenPaaSId("domain-A")).block();
        JwtToken jwtToken2 = testee.generate(new OpenPaaSId("domain-B")).block();

        String payload1 = decodeJWTPayloadPart(jwtToken1.value());
        String payload2 = decodeJWTPayloadPart(jwtToken2.value());

        JsonNode json1 = new ObjectMapper().readTree(payload1);
        JsonNode json2 = new ObjectMapper().readTree(payload2);

        assertThat(json1.get("domainId").asText()).isEqualTo("domain-A");
        assertThat(json2.get("domainId").asText()).isEqualTo("domain-B");

        assertThat(payload1).isNotEqualTo(payload2);
    }

    @Test
    void generateShouldSetCorrectExpiration() throws JsonProcessingException {
        Instant now = Instant.parse("2099-01-01T00:00:00Z");
        Duration expiration = Duration.ofSeconds(120);
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));

        TechnicalTokenService.Impl technicalTokenService =
            new TechnicalTokenService.Impl("my-secret", expiration, fixedClock);

        JwtToken jwtToken = technicalTokenService.generate(new OpenPaaSId("domain-id")).block();

        String payload = decodeJWTPayloadPart(jwtToken.value());
        JsonNode json = new ObjectMapper().readTree(payload);

        long expectedExp = now.plus(expiration).getEpochSecond();
        assertThat(json.get("exp").asLong()).isEqualTo(expectedExp);
    }

    @Test
    void claimShouldThrowWhenTokenIsExpired() {
        OpenPaaSId domainId = new OpenPaaSId("test-domain-id");
        Clock fixedClock = Clock.fixed(Instant.now().minusSeconds(10), ZoneId.of("UTC"));

        TechnicalTokenService.Impl tokenServiceWithExpiredClock =
            new TechnicalTokenService.Impl("my-secret",
                Duration.ofSeconds(2), fixedClock);

        TechnicalTokenService.JwtToken jwtToken = tokenServiceWithExpiredClock.generate(domainId).block();

        assertThatThrownBy(() -> tokenServiceWithExpiredClock.claim(jwtToken).block())
            .isInstanceOf(TechnicalTokenClaimException.class)
            .hasMessageContaining("Unable to claim technical token")
            .hasMessageContaining("The Token has expired");
    }

    @Test
    void claimShouldFailWhenTokenIsTampered() {
        JwtToken validToken = testee.generate(new OpenPaaSId("domain-id")).block();

        String[] parts = validToken.value().split("\\.");
        String fakePayload = BaseEncoding.base64Url().encode("{\"some\":\"tampered\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedTokenValue = parts[0] + "." + fakePayload + "." + parts[2];

        JwtToken tamperedToken = new JwtToken(tamperedTokenValue);

        assertThatThrownBy(() -> testee.claim(tamperedToken).block())
            .isInstanceOf(TechnicalTokenClaimException.class)
            .hasMessageContaining("Unable to claim technical token");
    }

    @Test
    void claimShouldFailWhenSignatureIsInvalid() {
        Clock clock = Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneId.of("UTC"));

        TechnicalTokenService.Impl tokenService1 = new TechnicalTokenService.Impl("secret-1", Duration.ofMinutes(2), clock);
        TechnicalTokenService.Impl tokenService2 = new TechnicalTokenService.Impl("secret-2", Duration.ofMinutes(2), clock);

        JwtToken tokenFromOtherSecret = tokenService1.generate(new OpenPaaSId("domain-id")).block();

        assertThatThrownBy(() -> tokenService2.claim(tokenFromOtherSecret).block())
            .isInstanceOf(TechnicalTokenClaimException.class)
            .hasMessageContaining("Unable to claim technical token");
    }

    @Test
    void claimShouldFailWhenTokenIsNotJwtFormat() {
        JwtToken invalidToken = new JwtToken("this-is-not-a-jwt");

        assertThatThrownBy(() -> testee.claim(invalidToken).block())
            .isInstanceOf(TechnicalTokenClaimException.class)
            .hasMessageContaining("Unable to claim technical token");
    }

    @Test
    void claimShouldFailWhenTokenIsNull() {
        assertThatThrownBy(() -> testee.claim(null).block())
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Token must not be null");
    }

    private String decodeJWTPayloadPart(String jwtToken) {
        String[] parts = jwtToken.split("\\.");
        return new String(BaseEncoding.base64Url().decode(parts[1]), StandardCharsets.UTF_8);
    }
}
