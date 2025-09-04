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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class PhotoUrlFactoryTest {

    private PhotoUrlFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        URL baseUrl = new URL("https://calendar.linagora.com/");
        factory = new PhotoUrlFactory(baseUrl);
    }

    @Test
    void resolveURLShouldBuildValidUri() {
        URI uri = factory.resolveURL("laptop");
        assertThat(uri.toString())
            .isEqualTo("https://calendar.linagora.com/linagora.esn.resource/images/icon/laptop.svg");
    }

    @Test
    void resolveURLShouldSupportHyphenAndUnderscore() {
        URI uri = factory.resolveURL("user_1-name");
        assertThat(uri.toString())
            .isEqualTo("https://calendar.linagora.com/linagora.esn.resource/images/icon/user_1-name.svg");
    }

    @Test
    void resolveURLShouldRejectEmptyIconName() {
        assertThatThrownBy(() -> factory.resolveURL(""))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("iconName");
    }

    @Test
    void resolveURLShouldRejectInvalidCharacters() {
        assertThatThrownBy(() -> factory.resolveURL("../etc/passwd"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("iconName");
    }
}
