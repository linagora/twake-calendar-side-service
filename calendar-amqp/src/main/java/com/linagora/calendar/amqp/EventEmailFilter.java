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

import java.io.FileNotFoundException;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.core.MailAddress;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;

public interface EventEmailFilter {

    Logger LOGGER = LoggerFactory.getLogger(EventEmailFilter.class);
    String ALLOWED_RECIPIENTS_PROPERTY = "mail.imip.recipient.whitelist";

    boolean shouldProcess(CalendarEventNotificationEmailDTO dto);

    static EventEmailFilter from(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            Configuration configuration = propertiesProvider.getConfiguration("configuration");
            Set<MailAddress> whiteList = Stream.of(configuration.getStringArray(ALLOWED_RECIPIENTS_PROPERTY))
                .map(Throwing.function(MailAddress::new))
                .collect(Collectors.toSet());

            if (!whiteList.isEmpty()) {
                return new WhitelistRecipientFilter(whiteList);
            } else {
                return acceptAll();
            }
        } catch (FileNotFoundException e) {
            LOGGER.info("Configuration file not found, allowed all recipients configured.");
            return acceptAll();
        }
    }

    static EventEmailFilter acceptAll() {
        return new NoOpEventEmailFilter();
    }

    class WhitelistRecipientFilter implements EventEmailFilter {

        private static final Logger LOGGER = LoggerFactory.getLogger(WhitelistRecipientFilter.class);

        private final Set<MailAddress> allowedRecipients;

        public WhitelistRecipientFilter(Set<MailAddress> allowedRecipients) {
            this.allowedRecipients = allowedRecipients;
            String allowedRecipientsString = allowedRecipients.stream()
                .map(MailAddress::asString)
                .collect(Collectors.joining(","));

            LOGGER.info("white list recipients: {}", allowedRecipientsString);
        }

        @Override
        public boolean shouldProcess(CalendarEventNotificationEmailDTO dto) {
            boolean result = allowedRecipients.contains(dto.recipientEmail());

            LOGGER.debug("Processing email for recipient {}: {}", dto.recipientEmail().asString(), result ? "allowed" : "not allowed");
            return result;
        }
    }

    class NoOpEventEmailFilter implements EventEmailFilter {
        @Override
        public boolean shouldProcess(CalendarEventNotificationEmailDTO dto) {
            return true; // No filtering, process all emails
        }
    }
}
