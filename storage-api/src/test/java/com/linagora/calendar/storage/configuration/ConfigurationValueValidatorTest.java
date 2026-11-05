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

package com.linagora.calendar.storage.configuration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BigIntegerNode;
import com.fasterxml.jackson.databind.node.DecimalNode;

class ConfigurationValueValidatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings = {
        "null", "true", "\"text\"", "12", "-9223372036854775808", "9223372036854775807", "1.5",
        "1.7976931348623157E308", "[1, 2.5, null]", "{\"a\":{\"b\":[9223372036854775807]}}"
    })
    void validateShouldAcceptStorableValues(String value) {
        assertThatCode(() -> ConfigurationValueValidator.validate(MAPPER.readTree(value)))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "18446744073709551616", "9223372036854775808", "-9223372036854775809", "1e4007", "-1e4007",
        "[4, 50000000000000000000]", "{\"a\":18446744073709551616}", "{\"a\":{\"b\":[1e4007]}}"
    })
    void validateShouldRejectNumbersThatCannotBeStored(String value) {
        assertThatThrownBy(() -> ConfigurationValueValidator.validate(MAPPER.readTree(value)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"a\\u0000b\":1}", "{\"\\u0000\":1}", "[{\"k\":{\"\\u0000\":1}}]"})
    void validateShouldRejectObjectKeysThatCannotBeStored(String value) {
        assertThatThrownBy(() -> ConfigurationValueValidator.validate(MAPPER.readTree(value)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validateShouldAcceptANulCharacterInAStringValue() {
        assertThatCode(() -> ConfigurationValueValidator.validate(MAPPER.readTree("{\"a\":\"b\\u0000c\"}")))
            .doesNotThrowAnyException();
    }

    @Test
    void validateShouldAcceptNullNode() {
        assertThatCode(() -> ConfigurationValueValidator.validate(null))
            .doesNotThrowAnyException();
    }

    @Test
    void validateShouldAcceptBigIntegerWithinLongRange() {
        assertThatCode(() -> ConfigurationValueValidator.validate(BigIntegerNode.valueOf(BigInteger.TEN)))
            .doesNotThrowAnyException();
    }

    @Test
    void validateShouldRejectBigDecimal() {
        assertThatThrownBy(() -> ConfigurationValueValidator.validate(DecimalNode.valueOf(BigDecimal.ONE)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
