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

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

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
    @JsonProperty("message") String message,
    @JsonProperty("oldMessage") Optional<String> oldMessage,
    @JsonProperty("hasChange") boolean hasChange,
    @JsonProperty("recipients") List<String> recipients) {

    /** Returns a copy of this DTO with a single recipient (used during fan-out). */
    public ItipLocalDeliveryDTO withSingleRecipient(String recipient) {
        return new ItipLocalDeliveryDTO(sender, method, uid, message, oldMessage, hasChange, List.of(recipient));
    }

    /** Strips the {@code mailto:} prefix from the sender address. */
    public String strippedSender() {
        return stripMailto(sender);
    }

    /** Strips the {@code mailto:} prefix from the single recipient address. */
    public String strippedRecipient() {
        return stripMailto(recipients.get(0));
    }

    private static String stripMailto(String address) {
        return address.startsWith("mailto:") ? address.substring(7) : address;
    }
}
