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

package com.linagora.calendar.storage.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccentInsensitivePrefixPatternTest {

    @ParameterizedTest
    @ValueSource(strings = {"Nguyễn", "김민준", "Ёлкин", "йогурт", "Ŀuis", "Théo", "Ẁalter", "ẻ"})
    void ofShouldFindANameByItself(String name) {
        assertThat(AccentInsensitivePrefixPattern.of(name).matcher(name + " Suffix").lookingAt()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ａnna", "ʰanh", "ℨoe", "ａnna"})
    void ofShouldFindANameHoldingACompatibilityLetterByItself(String name) {
        assertThat(AccentInsensitivePrefixPattern.of(name).matcher(name + " Suffix").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAnAsciiNameFromAFullWidthQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("Ａnna").matcher("Anna").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAFullWidthNameFromALowerCaseFullWidthQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("ａnna").matcher("Ａnna").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAVietnameseNameWithoutAccents() {
        assertThat(AccentInsensitivePrefixPattern.of("nguyen").matcher("Nguyễn").lookingAt()).isTrue();
    }

    @Test
    void ofShouldIgnoreCaseForCyrillicNames() {
        assertThat(AccentInsensitivePrefixPattern.of("ёлкин").matcher("Ёлкин").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindADecomposedName() {
        assertThat(AccentInsensitivePrefixPattern.of("Hélène").matcher("Hélène").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindADecomposedNameWithoutAccents() {
        assertThat(AccentInsensitivePrefixPattern.of("Helene").matcher("Hélène").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAComposedNameFromADecomposedQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("Hélène").matcher("Hélène").lookingAt()).isTrue();
    }

    @Test
    void ofShouldNotMatchDifferentLetters() {
        assertThat(AccentInsensitivePrefixPattern.of("김민").matcher("김준").lookingAt()).isFalse();
    }

    @Test
    void ofShouldTreatRegexCharactersLiterally() {
        assertThat(AccentInsensitivePrefixPattern.of("th.o").matcher("Théo").lookingAt()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Adébáyọ̀", "t́", "é́", "ọ̀́"})
    void ofShouldFindTheUnaccentedNameWhenTheQueryHoldsUncomposableMarks(String query) {
        // "Adébáyọ̀" ends with U+1ECD U+0300, which NFC cannot compose
        String unaccented = StringUtils.stripAccents(query);
        assertThat(AccentInsensitivePrefixPattern.of(query).matcher(unaccented + " Suffix").lookingAt()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Adébáyọ̀", "t́", "é́"})
    void ofShouldFindANameHoldingUncomposableMarksByItself(String name) {
        assertThat(AccentInsensitivePrefixPattern.of(name).matcher(name + " Suffix").lookingAt()).isTrue();
    }

    @Test
    void ofShouldKeepMarksFollowingNonLatinCharacters() {
        assertThat(AccentInsensitivePrefixPattern.of("कि").matcher("क").lookingAt()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u095E\u093E\u0924\u093F\u092E\u093E", // फ़ातिमा with the precomposed फ़ (composition exclusion)
        "\u0958\u093E\u0938\u093F\u092E", // क़ासिम with the precomposed क़
        "\u110C\u116F\u11B3", // 줤 stored decomposed (NFD)
        "\uFA01", // CJK compatibility ideograph, NFC maps it to U+5EA6
        "\uFA01\u0301\uFFFD"
    })
    void ofShouldFindANameTypedExactlyAsStored(String name) {
        assertThat(AccentInsensitivePrefixPattern.of(name).matcher(name + " Suffix").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindADecomposedHangulNameFromAComposedQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("\uC924").matcher("\u110C\u116F\u11B3").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAComposedHangulNameFromADecomposedQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("\u110C\u116F\u11B3").matcher("\uC924").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindADecomposedNuktaNameFromAPrecomposedQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("\u095E\u093E").matcher("\u092B\u093C\u093E").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindACompatibilityIdeographNameFromItsCanonicalForm() {
        assertThat(AccentInsensitivePrefixPattern.of("\uFA01").matcher("\u5EA6").lookingAt()).isTrue();
    }

    @Test
    void ofShouldFindAComposedCyrillicNameFromADecomposedQuery() {
        assertThat(AccentInsensitivePrefixPattern.of("\u0438\u0306").matcher("\u0439").lookingAt()).isTrue();
    }

    @Test
    void ofShouldNotMatchADifferentHangulSyllable() {
        assertThat(AccentInsensitivePrefixPattern.of("\uC924").matcher("\u110C\u116F\u11B2").lookingAt()).isFalse();
    }
}
