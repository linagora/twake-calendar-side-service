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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.commons.configuration2.BaseConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

class MailTemplateConfigurationTest {
    private static final String TEMPLATE_LOCATION = "classpath://templates/";

    @Test
    void fromShouldReadSender() throws Exception {
        BaseConfiguration configuration = new BaseConfiguration();
        configuration.addProperty("mail.template.location", TEMPLATE_LOCATION);
        configuration.addProperty("mail.sender", "no-reply@twake.app");

        MailTemplateConfiguration testee = MailTemplateConfiguration.from(configuration);

        assertThat(testee.sender()).isEqualTo(MaybeSender.of(new MailAddress("no-reply@twake.app")));
    }

    @Test
    void senderWithoutPlaceholderShouldNotDependOnTheDomain() throws Exception {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION, "no-reply@twake.app");

        assertThat(testee.isSenderPerDomain()).isFalse();
        assertThat(testee.sender(Domain.of("domain-b.tld")))
            .isEqualTo(MaybeSender.of(new MailAddress("no-reply@twake.app")));
        assertThat(testee.sender(Username.of("bob")))
            .isEqualTo(MaybeSender.of(new MailAddress("no-reply@twake.app")));
        assertThat(testee.senderAddress(Username.of("bob@domain-b.tld")))
            .isEqualTo(new MailAddress("no-reply@twake.app"));
    }

    @Test
    void compatibilityConstructorShouldKeepTheSender() throws Exception {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION,
            MaybeSender.getMailSender("no-reply@twake.app"));

        assertThat(testee.sender()).isEqualTo(MaybeSender.of(new MailAddress("no-reply@twake.app")));
    }

    @Test
    void compatibilityConstructorShouldKeepNullSender() {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION, MaybeSender.nullSender());

        assertThat(testee.sender().isNullSender()).isTrue();
        assertThatThrownBy(() -> testee.senderAddress(Domain.of("domain-b.tld")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void senderShouldReplacePlaceholderWithDomain() throws Exception {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION, "no-reply@{domain}");

        assertThat(testee.isSenderPerDomain()).isTrue();
        assertThat(testee.sender(Domain.of("domain-a.tld")))
            .isEqualTo(MaybeSender.of(new MailAddress("no-reply@domain-a.tld")));
        assertThat(testee.senderAddress(Username.of("bob@domain-b.tld")))
            .isEqualTo(new MailAddress("no-reply@domain-b.tld"));
    }

    @Test
    void senderWithoutDomainShouldFailWhenPlaceholder() {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION, "no-reply@{domain}");

        assertThatThrownBy(testee::sender)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void senderShouldFailForUsernameWithoutDomainPartWhenPlaceholder() {
        MailTemplateConfiguration testee = new MailTemplateConfiguration(TEMPLATE_LOCATION, "no-reply@{domain}");

        assertThatThrownBy(() -> testee.sender(Username.of("bob")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectInvalidSenderTemplate() {
        assertThatThrownBy(() -> new MailTemplateConfiguration(TEMPLATE_LOCATION, "no reply {domain}"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
