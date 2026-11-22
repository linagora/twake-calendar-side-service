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

import static com.linagora.calendar.restapi.routes.BookingLinkRequestConstraints.STRICT_OBJECT_MAPPER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.restapi.routes.BookingLinkCreateRoute.CreateBookingLinkRequestDTO;
import com.linagora.calendar.restapi.routes.BookingLinkRequestConstraints.TextField;
import com.linagora.calendar.storage.booking.BookingLinkInsertRequest;

class BookingLinkRequestConstraintsTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    @Test
    void toDurationShouldAcceptTheMaximumDuration() {
        assertThat(BookingLinkRequestConstraints.toDuration((int) Duration.ofDays(60).toMinutes()))
            .isEqualTo(Duration.ofDays(60));
    }

    @Test
    void toDurationShouldRejectDurationsAboveTheMaximum() {
        assertThatThrownBy(() -> BookingLinkRequestConstraints.toDuration(100000))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'durationMinutes' must not exceed 86400 (60 days)");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void toDurationShouldRejectNonPositiveDurations(int durationMinutes) {
        assertThatThrownBy(() -> BookingLinkRequestConstraints.toDuration(durationMinutes))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'durationMinutes' must be positive");
    }

    @Test
    void normalizeShouldTrimAndAcceptTextsAtTheMaximumLength() {
        String name = "a".repeat(TextField.NAME.maxLength());

        assertThat(TextField.NAME.normalize(Optional.of("  " + name + "  "))).contains(name);
    }

    @Test
    void normalizeShouldDropBlankTexts() {
        assertThat(TextField.LOCATION.normalize(Optional.of("   "))).isEmpty();
    }

    @Test
    void normalizeShouldRejectTooLongTexts() {
        String description = "a".repeat(TextField.DESCRIPTION.maxLength() + 1);

        assertThatThrownBy(() -> TextField.DESCRIPTION.normalize(Optional.of(description)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'description' must not exceed 4096 characters");
    }

    @Test
    void createShouldRejectTooLongName() throws Exception {
        CreateBookingLinkRequestDTO dto = STRICT_OBJECT_MAPPER.readValue("""
            {"calendarUrl":"/calendars/user/cal","durationMinutes":30,"active":true,"name":"%s"}"""
                .formatted("a".repeat(5000)),
            CreateBookingLinkRequestDTO.class);

        assertThatThrownBy(() -> CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, UTC, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("'name' must not exceed 255 characters");
    }

    @Test
    void createShouldRejectTooLongDuration() throws Exception {
        CreateBookingLinkRequestDTO dto = STRICT_OBJECT_MAPPER.readValue("""
            {"calendarUrl":"/calendars/user/cal","durationMinutes":100000,"active":true}""",
            CreateBookingLinkRequestDTO.class);

        assertThatThrownBy(() -> CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, UTC, Optional.empty()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageStartingWith("'durationMinutes' must not exceed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"active\":0",
        "\"active\":-1",
        "\"active\":9223372036854775808",
        "\"active\":\"true\"",
        "\"active\":true,\"autoAccept\":1",
        "\"active\":true,\"name\":0",
        "\"active\":true,\"description\":true",
        "\"active\":true,\"location\":1.5"
    })
    void strictMapperShouldRejectWrongJsonTypes(String fields) {
        String body = "{\"calendarUrl\":\"/calendars/user/cal\",\"durationMinutes\":30," + fields + "}";

        assertThatThrownBy(() -> STRICT_OBJECT_MAPPER.readValue(body, CreateBookingLinkRequestDTO.class))
            .isInstanceOf(Exception.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"durationMinutes\":\"30\"",
        "\"durationMinutes\":30.5",
        "\"durationMinutes\":true"
    })
    void strictMapperShouldRejectNonIntegerDurations(String durationField) {
        String body = "{\"calendarUrl\":\"/calendars/user/cal\",\"active\":true," + durationField + "}";

        assertThatThrownBy(() -> STRICT_OBJECT_MAPPER.readValue(body, CreateBookingLinkRequestDTO.class))
            .isInstanceOf(Exception.class);
    }

    @Test
    void asInvalidBodyShouldNameTheOffendingField() {
        String body = "{\"calendarUrl\":\"/calendars/user/cal\",\"durationMinutes\":30,\"active\":0}";

        assertThatThrownBy(() -> {
            try {
                STRICT_OBJECT_MAPPER.readValue(body, CreateBookingLinkRequestDTO.class);
            } catch (Exception e) {
                throw BookingLinkRequestConstraints.asInvalidBody(e);
            }
        })
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid request body: 'active' has an invalid value or JSON type");
    }

    @Test
    void asInvalidBodyShouldKeepTheGenericMessageForUnknownFields() {
        String body = "{\"calendarUrl\":\"/calendars/user/cal\",\"durationMinutes\":30,\"active\":true,\"unknown\":1}";

        assertThatThrownBy(() -> {
            try {
                STRICT_OBJECT_MAPPER.readValue(body, CreateBookingLinkRequestDTO.class);
            } catch (Exception e) {
                throw BookingLinkRequestConstraints.asInvalidBody(e);
            }
        })
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid request body");
    }

    @Test
    void strictMapperShouldAcceptWellTypedRequests() throws Exception {
        CreateBookingLinkRequestDTO dto = STRICT_OBJECT_MAPPER.readValue("""
            {"calendarUrl":"/calendars/user/cal","durationMinutes":30,"active":false,"autoAccept":true,
             "name":" Meeting ","description":"Desc","location":"Room 1"}""",
            CreateBookingLinkRequestDTO.class);

        BookingLinkInsertRequest insertRequest = CreateBookingLinkRequestDTO.toBookingLinkInsertRequest(dto, UTC, Optional.empty());

        assertThat(insertRequest.eventDuration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(insertRequest.active()).isFalse();
        assertThat(insertRequest.name()).contains("Meeting");
    }
}
