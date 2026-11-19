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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DavPathSegmentTest {

    @Test
    void validateShouldAcceptALongSegment() {
        String segment = "a%41".repeat(100_000);

        assertThat(DavPathSegment.validate(segment, "id")).isEqualTo(segment);
    }

    @Test
    void validateShouldRejectALongSegmentEndingWithATruncatedTriplet() {
        assertThatThrownBy(() -> DavPathSegment.validate("a%41".repeat(100_000) + "%4", "id"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5f1a2b3c4d5e6f7a8b9c0d1e", "a%C3%A9b", "%e2%82%ac", "a.b", "A-Z_0~9!$&'()*+,;=:@"})
    void validateShouldAcceptPcharSegments(String segment) {
        assertThat(DavPathSegment.validate(segment, "id")).isEqualTo(segment);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%", "%4", "a%", "%G1", "%1g", "%\u0664\u0661", "\u00e9", "a%2", "%%41", "a/b", "a%C3"})
    void validateShouldRejectNonPcharSegments(String segment) {
        assertThatThrownBy(() -> DavPathSegment.validate(segment, "id"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateShouldRejectNull() {
        assertThatThrownBy(() -> DavPathSegment.validate(null, "id"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
