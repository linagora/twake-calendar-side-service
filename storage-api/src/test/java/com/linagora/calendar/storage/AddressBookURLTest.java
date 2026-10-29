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

class AddressBookURLTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "base1/collected",
        "/base1/collected",
        "/addressbooks/base1/collected",
        "/addressbooks/base1/collected/",
    })
    void parseShouldAcceptVariousFormats(String input) {
        assertThat(AddressBookURL.parse(input))
            .isEqualTo(new AddressBookURL(new OpenPaaSId("base1"), "collected"));
    }

    @Test
    void parseShouldRejectInvalidFormat() {
        assertThatThrownBy(() -> AddressBookURL.parse("baseOnly"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseShouldRejectEmptyInput() {
        assertThatThrownBy(() -> AddressBookURL.parse(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/addressbooks/ac/..", "/addressbooks/c/.", "/addressbooks/a?b/c", "/addressbooks/a#b/c", "/addressbooks/%/c", "/addressbooks/a b/c", "/addressbooks/e/#", "/addressbooks/a\\b/c"})
    void parseShouldRejectIdsThatAreNotSinglePathSegments(String rawValue) {
        assertThatThrownBy(() -> AddressBookURL.parse(rawValue))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/addressbooks/base1/a.b", "/addressbooks/base1/a%20b", "/addressbooks/base1/user@domain.tld", "/addressbooks/base1/a-b_c~d+e"})
    void parseShouldAcceptIdsThatAreSinglePathSegments(String rawValue) {
        assertThat(AddressBookURL.parse(rawValue).asUri().getRawPath())
            .isEqualTo(rawValue);
    }

    @Test
    void constructorShouldRejectDotSegments() {
        assertThatThrownBy(() -> new AddressBookURL(new OpenPaaSId("base1"), ".."))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
