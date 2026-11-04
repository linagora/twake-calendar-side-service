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

package com.linagora.calendar.amqp.model;

import java.util.Optional;

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;

import com.linagora.calendar.amqp.EventMailHandlerException;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.MailtoUri;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;

public interface CalendarEventBookingNotificationEmail {
    String X_PUBLICLY_CREATOR_HEADER = "X-PUBLICLY-CREATOR";
    String PROPOSAL_OWNER_YOU_KEY = "proposal_owner_you";

    /**
     * A booking mail can only be rendered when its proposer is readable and is either the recipient
     * or one of the attendees (to resolve its display name).
     */
    static boolean hasResolvableProposer(VEvent event, MailAddress recipientEmail) {
        return proposerEmail(event)
            .filter(proposer -> isSameAddress(proposer, recipientEmail) || isAttendee(event, proposer))
            .isPresent();
    }

    private static Optional<MailAddress> proposerEmail(VEvent event) {
        return EventParseUtils.getPropertyValueIgnoreCase(event, X_PUBLICLY_CREATOR_HEADER)
            .map(MailtoUri::stripMailtoPrefix)
            .flatMap(CalendarEventBookingNotificationEmail::asMailAddress);
    }

    private static Optional<MailAddress> asMailAddress(String value) {
        try {
            return Optional.of(new MailAddress(value));
        } catch (AddressException e) {
            return Optional.empty();
        }
    }

    private static boolean isAttendee(VEvent event, MailAddress email) {
        return EventParseUtils.getAttendees(event)
            .stream()
            .anyMatch(person -> isSameAddress(person.email(), email));
    }

    private static boolean isSameAddress(MailAddress first, MailAddress second) {
        return first.asString().equalsIgnoreCase(second.asString());
    }

    CalendarEventNotificationEmail base();

    default MailAddress proposerEmail() {
        return proposerEmail(base().getFirstVEvent())
            .orElseThrow(() -> new EventMailHandlerException("Invalid " + X_PUBLICLY_CREATOR_HEADER + " for public agenda event"));
    }

    default String proposerDisplayName(MailAddress proposerEmail, MailAddress recipientEmail, I18NTranslator translator) {
        if (isSameAddress(proposerEmail, recipientEmail)) {
            return translator.get(PROPOSAL_OWNER_YOU_KEY);
        }

        return EventParseUtils.getAttendees(base().getFirstVEvent())
            .stream()
            .filter(person -> isSameAddress(person.email(), proposerEmail))
            .findFirst()
            .map(person -> PersonModel.from(person).displayName())
            .orElseThrow(() -> new EventMailHandlerException("Can not resolve proposer display name from attendees for public agenda event"));
    }
}
