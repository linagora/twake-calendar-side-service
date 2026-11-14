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
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.DomainUrl;

/**
 * @param senderTemplate the sender of the notification mails. It may contain the
 *                       {@value DomainUrl#DOMAIN_PLACEHOLDER} placeholder, e.g. {@code no-reply@{domain}}, replaced by
 *                       the domain of the user the mail is about.
 */
public record MailTemplateConfiguration(String templateLocationPath,
                                        String senderTemplate) {
    private static final String TEMPLATE_LOCATION = "mail.template.location";
    private static final String SENDER = "mail.sender";
    private static final Domain SAMPLE_DOMAIN = Domain.of("example.com");

    public static MailTemplateConfiguration from(Configuration configuration) {
        String templateLocation = Optional.ofNullable(configuration.getString(TEMPLATE_LOCATION, null))
            .filter(StringUtils::isNotEmpty)
            .orElseThrow(() -> new IllegalArgumentException("'" + TEMPLATE_LOCATION + "'is compulsory"));
        String senderString = Optional.ofNullable(configuration.getString(SENDER, null))
            .orElseThrow(() -> new IllegalArgumentException("'" + TEMPLATE_LOCATION + "'is compulsory"));
        return new MailTemplateConfiguration(templateLocation, senderString);
    }

    public MailTemplateConfiguration {
        Preconditions.checkNotNull(senderTemplate, "'senderTemplate' must not be null");
        if (senderTemplate.contains(DomainUrl.DOMAIN_PLACEHOLDER)) {
            Preconditions.checkArgument(!resolve(senderTemplate, SAMPLE_DOMAIN).isNullSender(),
                "'%s' is not a valid sender template", senderTemplate);
        }
    }

    public MailTemplateConfiguration(String templateLocationPath, MaybeSender sender) {
        this(templateLocationPath, sender.asString(""));
    }

    private static MaybeSender resolve(String senderTemplate, Domain domain) {
        return MaybeSender.getMailSender(senderTemplate.replace(DomainUrl.DOMAIN_PLACEHOLDER, domain.asString()));
    }

    public boolean isSenderPerDomain() {
        return senderTemplate.contains(DomainUrl.DOMAIN_PLACEHOLDER);
    }

    /**
     * @throws IllegalStateException if the sender contains the placeholder: use {@link #sender(Domain)} instead
     */
    public MaybeSender sender() {
        Preconditions.checkState(!isSenderPerDomain(),
            "Sender '%s' contains %s and must be resolved against a domain", senderTemplate, DomainUrl.DOMAIN_PLACEHOLDER);
        return MaybeSender.getMailSender(senderTemplate);
    }

    public MaybeSender sender(Domain domain) {
        if (!isSenderPerDomain()) {
            return sender();
        }
        Preconditions.checkNotNull(domain, "'domain' must not be null");
        return resolve(senderTemplate, domain);
    }

    /**
     * @throws IllegalArgumentException if the sender contains the placeholder and the username has no domain part
     */
    public MaybeSender sender(Username username) {
        if (!isSenderPerDomain()) {
            return sender();
        }
        return sender(username.getDomainPart()
            .orElseThrow(() -> new IllegalArgumentException("Cannot resolve sender '" + senderTemplate
                + "' for '" + username.asString() + "': no domain part")));
    }

    public MailAddress senderAddress(Domain domain) {
        return asSenderAddress(sender(domain));
    }

    public MailAddress senderAddress(Username username) {
        return asSenderAddress(sender(username));
    }

    private static MailAddress asSenderAddress(MaybeSender sender) {
        return sender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
    }
}