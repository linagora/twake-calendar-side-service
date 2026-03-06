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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

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
            .isInstanceOf(NullPointerException.class)
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
            .isInstanceOf(NullPointerException.class)
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

    private ReservationRequestDTO parse(String payload) {
        return ReservationRequestDTO.parse(payload.getBytes(StandardCharsets.UTF_8));
    }
}
