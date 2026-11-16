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

/**
 * Makes ical4j read the line break escapes of TEXT values as RFC 5545 defines them.
 *
 * <p>ical4j decodes a TEXT value in separate passes, and its line break pass skips a {@code \n} preceded by a
 * backslash, without working out whether that backslash is itself escaped: {@code C:\\temp\\\nnext} (backslash, line
 * break) is read as {@code C:\temp\\nnext}, which is also how the literal text {@code C:\temp\\nnext} is read. Nor does it
 * decode the upper case {@code \N} escape.
 *
 * <p>Such values thus get their line break escapes decoded here, in a single left to right pass, into real line
 * breaks: ical4j then only decodes the remaining escapes ({@code \\}, {@code \,}, {@code \;}), which it does
 * correctly.
 */
final class TextNewlineEscapes {
    private static final char BACKSLASH = '\\';
    private static final char LINE_BREAK = '\n';

    /**
     * Whether ical4j would misread a line break escape of the given raw TEXT value: an upper case {@code \N}, or a
     * {@code \n} right after an escaped backslash.
     */
    static boolean misreadByIcal4j(String value) {
        boolean afterEscapedBackslash = false;
        for (int i = 0; i < value.length() - 1; i++) {
            if (value.charAt(i) != BACKSLASH) {
                afterEscapedBackslash = false;
                continue;
            }
            char escaped = value.charAt(i + 1);
            if (escaped == 'N' || (escaped == 'n' && afterEscapedBackslash)) {
                return true;
            }
            afterEscapedBackslash = escaped == BACKSLASH;
            i++;
        }
        return false;
    }

    /**
     * Replaces the line break escapes of the given raw TEXT value by line breaks, and keeps every other escape.
     */
    static String decodeLineBreaks(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == BACKSLASH && i + 1 < value.length()) {
                char escaped = value.charAt(++i);
                if (escaped == 'n' || escaped == 'N') {
                    decoded.append(LINE_BREAK);
                } else {
                    decoded.append(current).append(escaped);
                }
            } else {
                decoded.append(current);
            }
        }
        return decoded.toString();
    }

    private TextNewlineEscapes() {
    }
}
