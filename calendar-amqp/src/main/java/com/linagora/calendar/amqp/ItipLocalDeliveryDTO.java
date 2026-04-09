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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * AMQP message payload for the {@code calendar:itip:localDelivery} exchange.
 *
 * <p>Published by {@code AMQPSchedulePlugin} (esn-sabre) after a PUT or DELETE on a calendar
 * object that has attendees. The consumer fans out N-recipient messages into N single-recipient
 * messages, then processes each one via Sabre's {@code POST /itip} endpoint.
 */
public record ItipLocalDeliveryDTO(
    @JsonProperty("sender") String sender,
    @JsonProperty("method") String method,
    @JsonProperty("uid") String uid,
    @JsonProperty("calendarId") String calendarId,
    @JsonProperty("message") String message,
    @JsonProperty("oldMessage") Optional<String> oldMessage,
    @JsonProperty("hasChange") boolean hasChange,
    @JsonDeserialize(using = RecipientListDeserializer.class)
    @JsonProperty("recipients") List<String> recipients) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    public static byte[] serialize(ItipLocalDeliveryDTO dto) {
        try {
            return OBJECT_MAPPER.writeValueAsBytes(dto);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize ItipLocalDeliveryDTO for uid " + dto.uid(), e);
        }
    }

    public static ItipLocalDeliveryDTO deserialize(byte[] payload) {
        try {
            return OBJECT_MAPPER.readValue(payload, ItipLocalDeliveryDTO.class);
        } catch (Exception e) {
            String preview = new String(payload, StandardCharsets.UTF_8);
            throw new RuntimeException("Failed to deserialize ItipLocalDeliveryDTO: " + StringUtils.left(preview, 512), e);
        }
    }

    private static class RecipientListDeserializer extends JsonDeserializer<List<String>> {
        @Override
        public List<String> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            if (!p.isExpectedStartArrayToken()) {
                p.skipChildren();
                return List.of();
            }
            List<String> recipients = new ArrayList<>();
            while (p.nextToken() != JsonToken.END_ARRAY) {
                if (p.currentToken() == JsonToken.VALUE_STRING) {
                    String value = p.getValueAsString();
                    // skip null or blank entries
                    if (value != null) {
                        String trimmed = value.trim();
                        if (!trimmed.isEmpty()) {
                            recipients.add(trimmed);
                        }
                    }
                } else {
                    p.skipChildren();
                }
            }
            return List.copyOf(recipients);
        }
    }

    /** Returns a copy of this DTO with a single recipient (used during fan-out). */
    public ItipLocalDeliveryDTO withSingleRecipient(String recipient) {
        return new ItipLocalDeliveryDTO(sender, method, uid, calendarId, message, oldMessage, hasChange, List.of(recipient));
    }

    /** Strips the {@code mailto:} prefix from the sender address. */
    public String strippedSender() {
        return stripMailto(sender);
    }

    /** Strips the {@code mailto:} prefix from the single recipient address. */
    public String strippedRecipient() {
        return stripMailto(recipients.getFirst());
    }

    private static String stripMailto(String address) {
        return address.startsWith("mailto:") ? address.substring(7) : address;
    }
}
