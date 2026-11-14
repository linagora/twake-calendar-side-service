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


package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.net.URI;
import java.net.URL;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.DomainUrl;

public class ResourcePhotoUrlFactoryTest {
    private static final Username USER = Username.of("bob@domain-b.tld");

    private ResourcePhotoUrlFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        URL baseUrl = new URL("https://calendar.linagora.com/");
        factory = new ResourcePhotoUrlFactory(baseUrl);
    }

    @Test
    void resolveURLShouldBuildValidUri() {
        URI uri = factory.resolveURL("laptop", USER);
        assertThat(uri.toString())
            .isEqualTo("https://calendar.linagora.com/images/icon/laptop.svg");
    }

    @Test
    void resolveURLShouldSupportHyphenAndUnderscore() {
        URI uri = factory.resolveURL("user_1-name", USER);
        assertThat(uri.toString())
            .isEqualTo("https://calendar.linagora.com/images/icon/user_1-name.svg");
    }

    @Test
    void resolveURLShouldRejectEmptyIconName() {
        assertThatThrownBy(() -> factory.resolveURL("", USER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("iconName");
    }

    @Test
    void resolveURLShouldRejectInvalidCharacters() {
        assertThatThrownBy(() -> factory.resolveURL("../etc/passwd", USER))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("iconName");
    }

    @Test
    void resolveURLShouldUseTheDomainOfTheUserWhenPlaceholder() {
        ResourcePhotoUrlFactory perDomainFactory = new ResourcePhotoUrlFactory(DomainUrl.parse("https://api.{domain}/"));

        assertThat(perDomainFactory.resolveURL("laptop", USER).toString())
            .isEqualTo("https://api.domain-b.tld/images/icon/laptop.svg");
    }
}
