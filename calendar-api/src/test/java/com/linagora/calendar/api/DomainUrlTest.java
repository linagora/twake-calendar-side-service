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


package com.linagora.calendar.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

class DomainUrlTest {

    @Test
    void parseShouldKeepUrlWithoutPlaceholderUnchanged() {
        DomainUrl testee = DomainUrl.parse("https://calendar.linagora.com/");

        assertThat(testee.isPerDomain()).isFalse();
        assertThat(testee.resolve(Domain.of("domain-b.tld")).toString())
            .isEqualTo("https://calendar.linagora.com/");
        assertThat(testee.resolve(Username.of("bob@domain-b.tld")).toString())
            .isEqualTo("https://calendar.linagora.com/");
        assertThat(testee.asUrl().toString())
            .isEqualTo("https://calendar.linagora.com/");
    }

    @Test
    void resolveShouldNotRequireDomainPartWithoutPlaceholder() {
        DomainUrl testee = DomainUrl.parse("https://calendar.linagora.com");

        assertThat(testee.resolve(Username.of("bob")).toString())
            .isEqualTo("https://calendar.linagora.com");
    }

    @Test
    void ofShouldWrapTheGivenUrl() throws Exception {
        DomainUrl testee = DomainUrl.of(URI.create("https://calendar.linagora.com").toURL());

        assertThat(testee.isPerDomain()).isFalse();
        assertThat(testee.resolve(Domain.of("domain-b.tld")).toString())
            .isEqualTo("https://calendar.linagora.com");
    }

    @Test
    void resolveShouldReplacePlaceholderWithDomain() {
        DomainUrl testee = DomainUrl.parse("https://calendar.{domain}/");

        assertThat(testee.isPerDomain()).isTrue();
        assertThat(testee.resolve(Domain.of("domain-a.tld")).toString())
            .isEqualTo("https://calendar.domain-a.tld/");
        assertThat(testee.resolve(Domain.of("domain-b.tld")).toString())
            .isEqualTo("https://calendar.domain-b.tld/");
    }

    @Test
    void resolveShouldUseTheDomainPartOfTheUsername() {
        DomainUrl testee = DomainUrl.parse("https://excal.{domain}");

        assertThat(testee.resolve(Username.of("bob@domain-b.tld")).toString())
            .isEqualTo("https://excal.domain-b.tld");
    }

    @Test
    void resolveShouldFailForUsernameWithoutDomainPartWhenPlaceholder() {
        DomainUrl testee = DomainUrl.parse("https://excal.{domain}");

        assertThatThrownBy(() -> testee.resolve(Username.of("bob")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void asUrlShouldFailWhenPlaceholder() {
        DomainUrl testee = DomainUrl.parse("https://excal.{domain}");

        assertThatThrownBy(testee::asUrl)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parseShouldRejectInvalidTemplate() {
        assertThatThrownBy(() -> DomainUrl.parse("not an url {domain}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseShouldRejectRelativeUrl() {
        assertThatThrownBy(() -> DomainUrl.parse("/calendar"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseShouldRejectBlankTemplate() {
        assertThatThrownBy(() -> DomainUrl.parse(" "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldMatchBeanContract() throws Exception {
        assertThat(DomainUrl.parse("https://calendar.{domain}"))
            .isEqualTo(DomainUrl.parse("https://calendar.{domain}"))
            .hasSameHashCodeAs(DomainUrl.parse("https://calendar.{domain}"))
            .isNotEqualTo(DomainUrl.parse("https://calendar.linagora.com"));
        assertThat(DomainUrl.of(URI.create("https://calendar.linagora.com").toURL()))
            .isEqualTo(DomainUrl.parse("https://calendar.linagora.com"));
    }
}
