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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.apache.commons.io.IOUtils;
import org.apache.james.jwt.JwtConfiguration;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEvent;
import com.linagora.calendar.api.BookedEventTokenSigner.BookedEventTokenClaimException;

public class BookedEventTokenSignerTest {

    private BookedEventTokenSigner testee;
    private Path privateKeyPath;
    private String publicKeyAsString;

    @BeforeEach
    void setUp() throws Exception {
        FileSystemImpl fileSystem = FileSystemImpl.forTesting();
        privateKeyPath = fileSystem.getFile("classpath://privatekey").toPath().toAbsolutePath();
        Path publicKeyPath = fileSystem.getFile("classpath://publickey").toPath().toAbsolutePath();
        publicKeyAsString = IOUtils.toString(publicKeyPath.toUri(), StandardCharsets.UTF_8);

        Duration expiration = Duration.ofHours(1);
        JwtSigner jwtSigner = new JwtSigner.Factory(
            Clock.systemUTC(),
            expiration,
            privateKeyPath,
            new RecordingMetricFactory())
            .instantiate();

        JwtVerifier jwtVerifier = JwtVerifier.create(new JwtConfiguration(List.of(publicKeyAsString)));
        testee = new BookedEventTokenSigner.Default(jwtSigner, jwtVerifier);
    }

    @Test
    void signAsJwtShouldReturnJWTToken() throws Exception {
        BookedEvent bookedEvent = sampleBookedEvent();
        String jwtToken = testee.signAsJwt(bookedEvent).block();
        assertThat(jwtToken).startsWith("eyJ");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(decodeJWTPayloadPart(jwtToken));

        assertThat(payload.get("publicBookingLinkId").asText()).isEqualTo(bookedEvent.publicBookingLinkId().toString());
        assertThat(payload.get("calendarId").asText()).isEqualTo(bookedEvent.calendarId());
        assertThat(payload.get("ownerId").asText()).isEqualTo(bookedEvent.ownerId());
        assertThat(payload.get("eventId").asText()).isEqualTo(bookedEvent.eventId());
    }

    @Test
    void signAndValidateShouldRoundTripSuccessfully() {
        BookedEvent bookedEvent = sampleBookedEvent();
        String jwt = testee.signAsJwt(bookedEvent).block();
        assertThat(testee.validateAndExtract(jwt).block())
            .isEqualTo(bookedEvent);
    }

    @Test
    void differentBookedEventsShouldProduceDifferentJwts() {
        BookedEvent bookedEvent1 = sampleBookedEvent();
        BookedEvent bookedEvent2 = new BookedEvent(
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            "event-uid-" + UUID.randomUUID());

        String jwt1 = testee.signAsJwt(bookedEvent1).block();
        String jwt2 = testee.signAsJwt(bookedEvent2).block();

        assertThat(jwt1).isNotEqualTo(jwt2);
        assertThat(testee.validateAndExtract(jwt1).block())
            .isNotEqualTo(testee.validateAndExtract(jwt2).block());
    }

    @Test
    void validateShouldFailOnInvalidToken() {
        String invalidToken = "not.a.jwt";
        assertThatThrownBy(() -> testee.validateAndExtract(invalidToken).block())
            .isInstanceOf(BookedEventTokenClaimException.class)
            .hasMessageContaining("Invalid booked event token");
    }

    @Test
    void validateShouldFailOnTamperedToken() {
        String validJwt = testee.signAsJwt(sampleBookedEvent()).block();
        String tamperedJWT = validJwt.substring(0, validJwt.length() - 1);

        assertThatThrownBy(() -> testee.validateAndExtract(tamperedJWT).block())
            .isInstanceOf(BookedEventTokenClaimException.class)
            .hasMessageContaining("Invalid booked event token");
    }

    @Test
    void signAsJwtShouldSetCorrectExpiration() throws Exception {
        Instant now = Instant.parse("2099-01-01T00:00:00Z");
        Duration expiration = Duration.ofSeconds(120);
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));

        JwtSigner jwtSigner = new JwtSigner.Factory(
            fixedClock,
            expiration,
            privateKeyPath,
            new RecordingMetricFactory()).instantiate();

        JwtVerifier jwtVerifier = JwtVerifier.create(new JwtConfiguration(List.of(publicKeyAsString)));
        testee = new BookedEventTokenSigner.Default(jwtSigner, jwtVerifier);

        String jwtToken = testee.signAsJwt(sampleBookedEvent()).block();
        String payload = decodeJWTPayloadPart(jwtToken);
        JsonNode json = new ObjectMapper().readTree(payload);

        long expectedExp = now.plus(expiration).getEpochSecond();
        assertThat(json.get("exp").asLong()).isEqualTo(expectedExp);
    }

    private String decodeJWTPayloadPart(String jwtToken) {
        String[] parts = jwtToken.split("\\.");
        return new String(BaseEncoding.base64Url().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private BookedEvent sampleBookedEvent() {
        return new BookedEvent(
            UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
            "calendar-id-abc",
            "owner-id-xyz",
            "event-uid-123");
    }
}
