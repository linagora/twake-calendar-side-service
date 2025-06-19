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

package com.linagora.calendar.smtp.template;

import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MaybeSender;

public record MailTemplateConfiguration(String templateLocationPath,
                                        MaybeSender sender) {
    private static final String TEMPLATE_LOCATION = "mail.template.location";
    private static final String SENDER = "mail.sender";


    private static MailTemplateConfiguration from(Configuration configuration) {
        String templateLocation = Optional.ofNullable(configuration.getString(TEMPLATE_LOCATION, null))
            .filter(StringUtils::isNotEmpty)
            .orElseThrow(() -> new IllegalArgumentException("'" + TEMPLATE_LOCATION + "'is compulsary"));
        String senderString = Optional.ofNullable(configuration.getString(SENDER, null))
            .orElseThrow(() -> new IllegalArgumentException("'" + TEMPLATE_LOCATION + "'is compulsary"));
        return new MailTemplateConfiguration(templateLocation, MaybeSender.getMailSender(senderString));
    }
}