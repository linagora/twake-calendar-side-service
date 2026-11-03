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

/**
 * Ensures a configuration value only holds numbers that can be stored and read back unchanged:
 * integers within the long range and finite floating point numbers.
 */
public final class ConfigurationValueValidator {

    public static void validate(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isNumber() && !isStorableNumber(node)) {
            throw new IllegalArgumentException("Unsupported number in configuration value: " + node);
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
