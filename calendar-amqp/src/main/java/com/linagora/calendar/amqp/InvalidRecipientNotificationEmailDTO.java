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

import java.util.Optional;

import org.apache.james.core.MailAddress;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.CalendarEventDeserializer;
import com.linagora.calendar.amqp.CalendarEventNotificationEmailDTO.MailAddressDeserializer;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.property.Method;

/**
 * A notification email message whose recipient is not a valid mail address: it cannot be sent,
 * so only what is needed to warn the organizer is read, and the recipient is kept as a raw string.
 */
public record InvalidRecipientNotificationEmailDTO(@JsonProperty("senderEmail") @JsonDeserialize(using = MailAddressDeserializer.class) MailAddress senderEmail,
                                                   @JsonProperty("recipientEmail") String recipientEmail,
                                                   @JsonProperty("method") Method method,
                                                   @JsonProperty("event") @JsonDeserialize(using = CalendarEventDeserializer.class) Calendar event,
                                                   @JsonProperty("eventPath") String eventPath,
                                                   @JsonProperty("isNewEvent") Optional<Boolean> isNewEvent) {

    public Optional<String> methodValue() {
        return Optional.ofNullable(method).map(Method::getValue);
    }

    public boolean isNewInvitation() {
        return methodValue().filter(Method.VALUE_REQUEST::equals).isPresent() && isNewEvent.orElse(false);
    }
}
