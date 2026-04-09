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

package com.linagora.calendar.amqp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class ItipLocalDeliveryDTOTest {

    @Test
    // This payload can appear when delete the event has no organizer field.
    void deserializeShouldFilterBlankRecipients() {
        byte[] payload = """
            {
                "sender": "mailto:user_2186a080-8f20-40f7-a557-6f51f6845662@open-paas.org",
                "method": "REPLY",
                "uid": "47d90176-b477-4fe1-91b3-a36ec0cfc67b",
                "message": "BEGIN:VCALENDAR",
                "hasChange": true,
                "recipients": [
                    ""
                ],
                "calendarId": "69d74abb8f3e5c321a999c75"
            }
            """.getBytes(StandardCharsets.UTF_8);

        ItipLocalDeliveryDTO actual = ItipLocalDeliveryDTO.deserialize(payload);
        assertThat(actual.recipients())
            .hasSize(0);
    }

    @Test
    void deserializeSerializedPayloadShouldReturnOriginalValue() {
        ItipLocalDeliveryDTO expected = new ItipLocalDeliveryDTO(
            "mailto:sender@example.com",
            "REQUEST",
            "uid-1",
            "calendar-1",
            "MSG",
            Optional.of("OLD_MSG"),
            true,
            List.of("mailto:alice@example.com"));

        byte[] payload = ItipLocalDeliveryDTO.serialize(expected);

        ItipLocalDeliveryDTO actual = ItipLocalDeliveryDTO.deserialize(payload);

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void deserializeShouldSucceedWhenAllFieldsArePresent() {
        byte[] payload = """
            {
                "sender": "mailto:sender@example.com",
                "method": "REQUEST",
                "uid": "uid-123",
                "calendarId": "calendar-123",
                "message": "BEGIN:VCALENDAR",
                "oldMessage": "BEGIN:VCALENDAR:OLD",
                "hasChange": true,
                "recipients": [
                    "mailto:alice@example.com",
                    "mailto:bob@example.com"
                ]
            }
            """.getBytes(StandardCharsets.UTF_8);

        ItipLocalDeliveryDTO actual = ItipLocalDeliveryDTO.deserialize(payload);

        ItipLocalDeliveryDTO expected = new ItipLocalDeliveryDTO(
            "mailto:sender@example.com",
            "REQUEST",
            "uid-123",
            "calendar-123",
            "BEGIN:VCALENDAR",
            Optional.of("BEGIN:VCALENDAR:OLD"),
            true,
            List.of("mailto:alice@example.com", "mailto:bob@example.com"));

        assertThat(actual).isEqualTo(expected);
    }
}