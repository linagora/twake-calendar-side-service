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

import jakarta.mail.internet.AddressException;

import org.apache.commons.io.IOUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.jwt.JwtConfiguration;
import org.apache.james.metrics.tests.RecordingMetricFactory;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.BaseEncoding;
import com.linagora.calendar.api.ParticipationTokenSigner.ParticipationTokenClaimException;

public class ParticipationTokenSignerTest {

    private ParticipationTokenSigner testee;
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
        testee = new ParticipationTokenSigner.Default(jwtSigner, jwtVerifier);
    }

    @Test
    void signAsJwtShouldReturnJWTToken() throws Exception {
        Participation participation = sampleParticipation();
        String jwtToken = testee.signAsJwt(participation).block();
        assertThat(jwtToken).startsWith("eyJ");

        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload = mapper.readTree(decodeJWTPayloadPart(jwtToken));

        assertThat(payload.get("uid").asText()).isEqualTo("event-uid-123");
        assertThat(payload.get("action").asText()).isEqualTo("ACCEPTED");
        assertThat(payload.get("organizerEmail").asText()).isEqualTo("organizer@domain.tld");
        assertThat(payload.get("attendeeEmail").asText()).isEqualTo("attendee@domain.tld");
        assertThat(payload.get("calendarURI").asText()).isEqualTo("calendar-uri-xyz");
    }

    @Test
    void signAndValidateShouldRoundTripSuccessfully() throws Exception {
        Participation participation = sampleParticipation();
        String jwt = testee.signAsJwt(participation).block();
        assertThat(testee.validateAndExtractParticipation(jwt).block())
            .isEqualTo(participation);
    }

    @Test
    void differentParticipationShouldProduceDifferentJwt() throws Exception {
        Participation participation1 = sampleParticipation();
        Participation participation2 = new Participation(
            new MailAddress(UUID.randomUUID() + "@domain.tld"),
            new MailAddress(UUID.randomUUID() + "@domain.tld"),
            "event-uid-" + UUID.randomUUID(),
            "calendar-uri-" + UUID.randomUUID(),
            Participation.ParticipantAction.ACCEPTED);

        String jwt1 = testee.signAsJwt(participation1).block();
        String jwt2 = testee.signAsJwt(participation2).block();

        assertThat(jwt1).isNotEqualTo(jwt2);
    }

    @Test
    void validateShouldFailOnInvalidToken() {
        String invalidToken = "not.a.jwt";
        assertThatThrownBy(() -> testee.validateAndExtractParticipation(invalidToken).block())
            .isInstanceOf(ParticipationTokenClaimException.class)
            .hasMessageContaining("Invalid participation token");
    }

    @Test
    void validateShouldFailOnTamperedToken() throws Exception {
        String validJwt = testee.signAsJwt(sampleParticipation()).block();
        String tamperedJWT = validJwt.substring(0, validJwt.length() - 1);

        assertThatThrownBy(() -> testee.validateAndExtractParticipation(tamperedJWT).block())
            .isInstanceOf(ParticipationTokenClaimException.class)
            .hasMessageContaining("Invalid participation token");
    }

    @Test
    void signAsJwtShouldSetCorrectExpiration() throws Exception {
        Participation participation = sampleParticipation();
        Instant now = Instant.parse("2099-01-01T00:00:00Z");
        Duration expiration = Duration.ofSeconds(120);
        Clock fixedClock = Clock.fixed(now, ZoneId.of("UTC"));

        JwtSigner jwtSigner = new JwtSigner.Factory(
            fixedClock,
            expiration,
            privateKeyPath,
            new RecordingMetricFactory()).instantiate();

        JwtVerifier jwtVerifier = JwtVerifier.create(new JwtConfiguration(List.of(publicKeyAsString)));
        testee = new ParticipationTokenSigner.Default(jwtSigner, jwtVerifier);

        String jwtToken = testee.signAsJwt(participation).block();
        String payload = decodeJWTPayloadPart(jwtToken);
        JsonNode json = new ObjectMapper().readTree(payload);

        long expectedExp = now.plus(expiration).getEpochSecond();
        assertThat(json.get("exp").asLong()).isEqualTo(expectedExp);
    }

    private String decodeJWTPayloadPart(String jwtToken) {
        String[] parts = jwtToken.split("\\.");
        return new String(BaseEncoding.base64Url().decode(parts[1]), StandardCharsets.UTF_8);
    }

    private Participation sampleParticipation() throws AddressException {
        return new Participation(
            new MailAddress("organizer@domain.tld"),
            new MailAddress("attendee@domain.tld"),
            "event-uid-123",
            "calendar-uri-xyz",
            Participation.ParticipantAction.ACCEPTED);
    }
}

