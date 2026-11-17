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

package com.linagora.calendar.api;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;

import net.fortuna.ical4j.model.Encodable;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyBuilder;
import net.fortuna.ical4j.model.PropertyFactory;

/**
 * A {@link PropertyBuilder} decoding the escapes of TEXT values in a single left to right pass, as RFC 5545 requires.
 *
 * <p>ical4j decodes them in successive replacement passes, and its pass for {@code \n} skips any {@code \n} preceded by
 * a backslash, without working out whether that backslash is itself escaped: {@code C:\\temp\\\nnext} (a backslash
 * followed by a line break) is read as {@code C:\temp\\nnext}, the very value {@code C:\\temp\\\\nnext} decodes to.
 *
 * <p>The values ical4j decodes, those of {@link Encodable} properties, are thus decoded again from the raw value.
 */
class TextDecodingPropertyBuilder extends PropertyBuilder {
    private static final char BACKSLASH = '\\';
    private static final char LINE_BREAK = '\n';

    private String rawValue = "";

    TextDecodingPropertyBuilder(List<PropertyFactory<? extends Property>> factories) {
        super(factories);
    }

    @Override
    public PropertyBuilder value(String value) {
        // ical4j trims the value as well
        this.rawValue = value.trim();
        return super.value(value);
    }

    @Override
    public Property build() {
        Property property = super.build();
        if (property instanceof Encodable) {
            property.setValue(decode(rawValue));
        }
        return property;
    }

    /**
     * Decodes {@code \\}, {@code \n}, {@code \N}, {@code \,}, {@code \;} and, like ical4j, {@code \"}. Other
     * backslashes are kept, like ical4j does.
     */
    @VisibleForTesting
    static String decode(String value) {
        if (value.indexOf(BACKSLASH) < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        int position = 0;
        while (position < value.length()) {
            char current = value.charAt(position);
            if (current == BACKSLASH && position + 1 < value.length()) {
                decoded.append(decodeEscape(value.charAt(position + 1)));
                position += 2;
            } else {
                decoded.append(current);
                position++;
            }
        }
        return decoded.toString();
    }

    private static String decodeEscape(char escaped) {
        return switch (escaped) {
            case 'n', 'N' -> String.valueOf(LINE_BREAK);
            case BACKSLASH, ',', ';', '"' -> String.valueOf(escaped);
            default -> String.valueOf(BACKSLASH) + escaped;
        };
    }
}
