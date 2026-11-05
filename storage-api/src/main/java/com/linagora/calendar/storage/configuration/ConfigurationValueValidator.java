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

import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;

/**
 * Ensures a configuration value can be stored and read back unchanged: its numbers are integers within the long range
 * or finite floating point numbers, and its object keys hold no NUL character, as BSON writes them as C strings.
 */
public final class ConfigurationValueValidator {
    private static final char NUL = '\0';

    public static void validate(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isNumber() && !isStorableNumber(node)) {
            throw new IllegalArgumentException("Unsupported number in configuration value: " + node);
        }
        if (Streams.stream(node.fieldNames()).anyMatch(key -> key.indexOf(NUL) >= 0)) {
            throw new IllegalArgumentException("Unsupported NUL character in a key of a configuration value");
        }
        StreamSupport.stream(node.spliterator(), false)
            .forEach(ConfigurationValueValidator::validate);
    }

    private static boolean isStorableNumber(JsonNode node) {
        if (node.isBigInteger()) {
            return node.canConvertToLong();
        }
        if (node.isBigDecimal()) {
            return false;
        }
        if (node.isFloatingPointNumber()) {
            return Double.isFinite(node.doubleValue());
        }
        return true;
    }

    private ConfigurationValueValidator() {
    }
}
