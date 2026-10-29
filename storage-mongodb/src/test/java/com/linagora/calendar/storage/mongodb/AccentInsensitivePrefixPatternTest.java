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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AccentInsensitivePrefixPatternTest {

    @ParameterizedTest
    @ValueSource(strings = {"Nguyễn", "김민준", "Ёлкин", "йогурт", "Ŀuis", "Théo", "Ẁalter", "ẻ"})
    void ofShouldFindANameByItself(String name) {
        assertThat(AccentInsensitivePrefixPattern.of(name).matcher(name + " Suffix").lookingAt()).isTrue();
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
}
