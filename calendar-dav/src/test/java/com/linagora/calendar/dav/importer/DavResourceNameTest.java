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

package com.linagora.calendar.dav.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DavResourceNameTest {

    @Test
    void fromUidShouldKeepSafeUid() {
        assertThat(DavResourceName.fromUid("event-1@example.com:20250101T0000"))
            .isEqualTo("event-1@example.com:20250101T0000");
    }

    @ParameterizedTest
    @ValueSource(strings = {"123%2f4", "1%", "a%zz", "%2e%2e%2fother"})
    void fromUidShouldNotKeepPercentSequences(String uid) {
        String name = DavResourceName.fromUid(uid);

        assertThat(name).doesNotContain("%");
        assertThatCode(() -> URI.create("/calendars/base/calendar/" + name + ".ics?import"))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"../other", "a/b", "a b", "a?b", "a#b"})
    void fromUidShouldReplaceUnsafeUid(String uid) {
        assertThat(DavResourceName.fromUid(uid)).isNotEqualTo(uid);
    }
}
