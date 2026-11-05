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
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.DavPathSegment;

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
    @ValueSource(strings = {"../other", "a/b", "a b", "a?b", "a#b", ".", "..", " .. ", "%2e", "%2E%2e", "%2f", "%00"})
    void fromUidShouldReplaceUnsafeUid(String uid) {
        assertThat(DavResourceName.fromUid(uid)).isNotEqualTo(uid);
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "..", "%2e", "%2E%2e", "%2f", "%5C", "%00", "a/b", "a\\b", "a%", "", " "})
    void isSafeShouldRefuseWhatIsNotASinglePathSegment(String value) {
        assertThat(DavResourceName.isSafe(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"...", ".a", "a.", "a..b", "a.vcf", "..a", "_", "~", ":", "@", "+", "-"})
    void isSafeShouldKeepOtherDottedAndPunctuatedNames(String value) {
        assertThat(DavResourceName.isSafe(value)).isTrue();
    }

    @Test
    void everySafeNameShouldBeAValidPathSegment() {
        // any name up to 3 characters of the allowed punctuation: what isSafe accepts DavPathSegment must accept too
        String alphabet = "a_.~:@+-";
        List<String> names = alphabet.chars().mapToObj(Character::toString).toList();
        List<String> pairs = names.stream().flatMap(left -> names.stream().map(right -> left + right)).toList();
        List<String> triples = pairs.stream().flatMap(left -> names.stream().map(right -> left + right)).toList();

        Stream.of(names, pairs, triples)
            .flatMap(List::stream)
            .filter(DavResourceName::isSafe)
            .forEach(name -> assertThatCode(() -> DavPathSegment.validate(name, "name"))
                .as(name)
                .doesNotThrowAnyException());
    }
}
