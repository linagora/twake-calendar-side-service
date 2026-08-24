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

import org.apache.james.core.MailAddress;

import com.linagora.calendar.amqp.EventMailHandlerException;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.event.EventParseUtils;

public interface CalendarEventBookingNotificationEmail {
    String X_PUBLICLY_CREATOR_HEADER = "X-PUBLICLY-CREATOR";
    String PROPOSAL_OWNER_YOU_KEY = "proposal_owner_you";

    CalendarEventNotificationEmail base();

    default MailAddress proposerEmail() {
        try {
            return new MailAddress(EventParseUtils.getPropertyValueIgnoreCase(base().getFirstVEvent(), X_PUBLICLY_CREATOR_HEADER)
                .orElseThrow());
        } catch (Exception e) {
            throw new EventMailHandlerException("Invalid " + X_PUBLICLY_CREATOR_HEADER + " for public agenda event", e);
        }
    }
    
    default String proposerDisplayName(MailAddress proposerEmail, MailAddress recipientEmail, I18NTranslator translator) {
        if (proposerEmail.asString().equalsIgnoreCase(recipientEmail.asString())) {
            return translator.get(PROPOSAL_OWNER_YOU_KEY);
        }

        return EventParseUtils.getAttendees(base().getFirstVEvent())
            .stream()
            .filter(person -> person.email().asString().equalsIgnoreCase(proposerEmail.asString()))
            .findFirst()
            .map(person -> PersonModel.from(person).displayName())
            .orElseThrow(() -> new EventMailHandlerException("Can not resolve proposer display name from attendees for public agenda event"));
    }
}
