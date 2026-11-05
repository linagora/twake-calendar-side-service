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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.james.mime4j.dom.address.Mailbox;
import org.apache.james.mime4j.field.Fields;
import org.apache.james.mime4j.field.address.LenientAddressParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.restapi.routes.BookingLinkReservationRoute.ReservationRequestDTO;
import com.linagora.calendar.restapi.routes.BookingLinkReservationRoute.ReservationRequestDTO.AttendeeDTO;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest;

public class BookingLinkReservationRequestDTOTest {
    private static final int MAX_ADDITIONAL_ATTENDEES = 20;
    private static final int MAX_TITLE_LENGTH = 255;
    private static final int MAX_NOTES_LENGTH = 2000;

    @Test
    void parseShouldDeserializeValidJson() {
        String payload = """
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "Nguyen Van A",
                  "email": "vana@example.com"
                }
              ],
              "eventTitle": "30-min intro call",
              "visioLink": true,
              "notes": "Please call via Zoom."
            }
            """;

        ReservationRequestDTO request = parse(payload);

        assertSoftly(softly -> {
            softly.assertThat(request)
                .isEqualTo(new ReservationRequestDTO(
                    Instant.parse("2036-01-26T09:30:00Z"),
                    new AttendeeDTO("BOB", "creator@example.com"),
                    Set.of(new AttendeeDTO("Nguyen Van A", "vana@example.com")),
                    "30-min intro call",
                    true,
                    "Please call via Zoom."));
            softly.assertThat(request.toBookingRequest().notes())
                .isEqualTo("Please call via Zoom.");
        });
    }

    @Test
    void parseShouldFailWhenJsonIsInvalid() {
        assertThatThrownBy(() -> ReservationRequestDTO.parse("{".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing or invalid request body");
    }

    @Test
    void toBookingRequestShouldFailWhenTitleExceedsLimit() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "%s",
              "visioLink": false
            }
            """.formatted("x".repeat(MAX_TITLE_LENGTH + 1)));

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'eventTitle' must not exceed " + MAX_TITLE_LENGTH + " characters");
    }

    @Test
    void toBookingRequestShouldFailWhenStartUtcIsNull() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": null,
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "eventTitle",
              "visioLink": false
            }
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'startUtc' must not be null");
    }

    @Test
    void parseShouldFailWhenStartUtcIsInvalid() {
        byte[] payload = """
            {
              "startUtc": "invalid-start",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "eventTitle"
            }
            """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ReservationRequestDTO.parse(payload))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing or invalid request body");
    }

    @Test
    void toBookingRequestShouldFailWhenCreatorIsNull() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": null,
              "eventTitle": "eventTitle",
              "visioLink": false
            }
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'creator' must not be null");
    }

    @Test
    void toBookingRequestShouldFailWhenTitleIsBlank() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": " ",
              "visioLink": false
            }
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'eventTitle' must not be blank");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u0001", "\\u0000\\u007f", "\\u0001 \\u0002", "\\r\\u0001\\n"})
    void toBookingRequestShouldFailWhenTitleHoldsOnlyControlCharacters(String jsonEscapedTitle) {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "%s"
            }
            """.formatted(jsonEscapedTitle));

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'eventTitle' must not be blank");
    }

    @Test
    void toBookingRequestShouldAcceptTitleHoldingVisibleTextAndControlCharacters() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "\\u0001Title"
            }
            """);

        assertThat(request.toBookingRequest().title()).isEqualTo("\u0001Title");
    }

    @Test
    void toBookingRequestShouldFailWhenAdditionalAttendeesExceedLimit() {
        String additionalAttendeesJson = IntStream.range(0, MAX_ADDITIONAL_ATTENDEES + 1)
            .mapToObj(i -> """
                {
                  "name": "name-%d",
                  "email": "a%d@example.com"
                }
                """.formatted(i, i))
            .collect(java.util.stream.Collectors.joining(","));

        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [%s],
              "eventTitle": "eventTitle",
              "visioLink": false
            }
            """.formatted(additionalAttendeesJson));

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'additional_attendees' must not exceed " + MAX_ADDITIONAL_ATTENDEES + " items");
    }

    @Test
    void toBookingRequestShouldFailWhenNotesExceedLimit() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "eventTitle",
              "visioLink": false,
              "notes": "%s"
            }
            """.formatted("n".repeat(MAX_NOTES_LENGTH + 1)));

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'notes' must not exceed " + MAX_NOTES_LENGTH + " characters");
    }

    @Test
    void toBookingRequestShouldFailWhenAttendeeEmailIsInvalid() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "Nguyen Van A",
                  "email": "invalid-email"
                }
              ],
              "eventTitle": "eventTitle",
              "visioLink": false
            }
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'email' has invalid format");
    }

    @Test
    void toBookingRequestShouldDeduplicateAdditionalAttendeesAndExcludeCreator() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "additional_attendees": [
                {
                  "name": "Creator Dup",
                  "email": "CREATOR@example.com"
                },
                {
                  "name": "Alice",
                  "email": "alice@example.com"
                },
                {
                  "name": "Alice Dup",
                  "email": "ALICE@example.com"
                }
              ],
              "eventTitle": "eventTitle",
              "visioLink": false
            }
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'additional_attendees' must not contain creator email");
    }

    @Test
    void additionalAttendeeEqualToCreatorAfterTrimmingShouldBeRejected() {
        byte[] body = """
            {"startUtc":"2026-11-03T14:15:00Z",
             "creator":{"name":"Carol","email":"carol@visitor.com"},
             "additional_attendees":[{"name":"Carol again","email":" carol@visitor.com"}],
             "eventTitle":"Demo"}
            """.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> ReservationRequestDTO.parse(body).toBookingRequest())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'additional_attendees' must not contain creator email");
    }

    @Test
    void additionalAttendeesEqualAfterTrimmingShouldBeRejected() {
        ReservationRequestDTO request = parse("""
            {"startUtc":"2036-01-26T09:30:00Z",
             "creator":{"name":"BOB","email":"creator@example.com"},
             "additional_attendees":[{"name":"A","email":"a@x.org"},{"name":"A again","email":" a@x.org"}],
             "eventTitle":"Demo"}
            """);

        assertThatThrownBy(request::toBookingRequest)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("'additional_attendees' contains duplicate email");
    }

    @Test
    void toBookingRequestShouldApplyDefaultsWhenOptionalFieldsAreMissing() {
        ReservationRequestDTO request = parse("""
            {
              "startUtc": "2036-01-26T09:30:00Z",
              "creator": {
                "name": "BOB",
                "email": "creator@example.com"
              },
              "eventTitle": "eventTitle"
            }
            """);

        BookingRequest bookingRequest = request.toBookingRequest();

        assertSoftly(softly -> {
            softly.assertThat(bookingRequest.visioLink()).isFalse();
            softly.assertThat(bookingRequest.additionalAttendees()).isEmpty();
        });
    }

    @Test
    void nullAdditionalAttendeeEmailShouldBeRejectedAsBadRequest() {
        assertThatThrownBy(() -> parse("""
            {"startUtc":"2036-01-26T09:30:00Z","creator":{"name":"BOB","email":"creator@example.com"},
             "additional_attendees":[{"name":"No mail","email":null}],"eventTitle":"Intro"}""").toBookingRequest())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullAdditionalAttendeeShouldBeRejectedAsBadRequest() {
        assertThatThrownBy(() -> parse("""
            {"startUtc":"2036-01-26T09:30:00Z","creator":{"name":"BOB","email":"creator@example.com"},
             "additional_attendees":[null],"eventTitle":"Intro"}""").toBookingRequest())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullBodyShouldBeRejectedAsBadRequest() {
        assertThatThrownBy(() -> parse("null"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bob\\\rx@example.com", "bob\\\nx@example.com", "g4--\\:\\\\t@exa.xn.org", "a\\b@example.com"})
    void creatorEmailThatCannotBeWrittenInAHeaderShouldBeRejected(String email) throws Exception {
        String payload = "{\"startUtc\":\"2036-01-26T09:30:00Z\",\"creator\":{\"email\":"
            + new ObjectMapper().writeValueAsString(email) + "},\"eventTitle\":\"Demo\"}";

        assertThatThrownBy(() -> parse(payload).toBookingRequest())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bob\\\rx@example.com", "bob\\\nx@example.com", "g4--\\:\\\\t@exa.xn.org", "a\\b@example.com"})
    void additionalAttendeeEmailThatCannotBeWrittenInAHeaderShouldBeRejected(String email) throws Exception {
        String payload = "{\"startUtc\":\"2036-01-26T09:30:00Z\",\"creator\":{\"email\":\"creator@example.com\"},"
            + "\"additional_attendees\":[{\"email\":" + new ObjectMapper().writeValueAsString(email) + "}],\"eventTitle\":\"Demo\"}";

        assertThatThrownBy(() -> parse(payload).toBookingRequest())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creatorMailboxShouldBeWrittenAsASingleReplyToMailbox() {
        BookingRequest bookingRequest = parse("""
            {"startUtc":"2036-01-26T09:30:00Z","creator":{"name":"BOB","email":"Bob.Smith+tag@example.com"},"eventTitle":"Intro"}""")
            .toBookingRequest();

        String replyTo = Fields.replyTo(bookingRequest.creator().asMailbox()).getBody();

        assertThat(LenientAddressParser.DEFAULT.parseAddressList(replyTo))
            .singleElement()
            .isInstanceOfSatisfying(Mailbox.class, mailbox -> assertThat(mailbox.getAddress()).isEqualTo("Bob.Smith+tag@example.com"));
    }

    private ReservationRequestDTO parse(String payload) {
        return ReservationRequestDTO.parse(payload.getBytes(StandardCharsets.UTF_8));
    }
}
