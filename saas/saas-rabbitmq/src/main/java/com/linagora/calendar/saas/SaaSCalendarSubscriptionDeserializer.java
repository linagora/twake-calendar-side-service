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

package com.linagora.calendar.saas;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public class SaaSCalendarSubscriptionDeserializer {

    public static class SaaSCalendarSubscriptionMessageParseException extends RuntimeException {
        public SaaSCalendarSubscriptionMessageParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static DomainSubscriptionMessage parseDomainMessage(byte[] message) {
        return parseDomainMessage(new String(message, StandardCharsets.UTF_8));
    }

    public static DomainSubscriptionMessage parseDomainMessage(String message) {
        try {
            return OBJECT_MAPPER.readValue(message, DomainSubscriptionMessage.class);
        } catch (Exception e) {
            throw new SaaSCalendarSubscriptionMessageParseException("Failed to parse domain subscription message: " + message, e);
        }
    }

    public static UserSubscriptionMessage parseUserMessage(byte[] message) {
        return parseUserMessage(new String(message, StandardCharsets.UTF_8));
    }

    public static UserSubscriptionMessage parseUserMessage(String message) {
        try {
            return OBJECT_MAPPER.readValue(message, UserSubscriptionMessage.class);
        } catch (Exception e) {
            throw new SaaSCalendarSubscriptionMessageParseException("Failed to parse user subscription message: " + message, e);
        }
    }
}
