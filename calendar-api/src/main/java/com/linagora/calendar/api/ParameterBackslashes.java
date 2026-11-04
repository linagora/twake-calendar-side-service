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

import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Makes ical4j read the backslashes of parameter values literally.
 *
 * <p>In RFC 5545 a backslash is a plain character of a parameter value (RFC 6868 escapes use {@code ^}). ical4j
 * however reads {@code \x} inside a quoted value as an escape for {@code x} ({@code CN="CORP\Zoé"} becomes
 * {@code CORPZoé}), and does not end an unquoted value on a {@code ;} preceded by a backslash
 * ({@code ROLE=CHAIR\;PARTSTAT=ACCEPTED} becomes a single ROLE parameter).
 *
 * <p>Each parameter value carrying a backslash is thus rewritten as a quoted value whose backslashes are doubled,
 * which ical4j reads back as the original value. Lines whose parameters do not follow the RFC 5545 grammar (a quote
 * inside an unquoted value, a list of values...) are left untouched.
 */
final class ParameterBackslashes {
    private static final char BACKSLASH = '\\';
    private static final char QUOTE = '"';
    private static final char PARAMETER_SEPARATOR = ';';
    private static final char VALUE_SEPARATOR = ':';
    private static final char NAME_SEPARATOR = '=';
    private static final String LINE_SEPARATOR = "\n";
    private static final Pattern FOLDING = Pattern.compile("\\r?\\n[ \\t]");

    static boolean mayNeedRewrite(byte[] icsContent) {
        for (byte b : icsContent) {
            if (b == BACKSLASH) {
                return true;
            }
        }
        return false;
    }

    static String rewrite(String icsContent) {
        String unfolded = FOLDING.matcher(icsContent).replaceAll("");
        return Arrays.stream(unfolded.split(LINE_SEPARATOR, -1))
            .map(ParameterBackslashes::rewriteLine)
            .collect(Collectors.joining(LINE_SEPARATOR));
    }

    private static String rewriteLine(String line) {
        int nameEnd = indexOfAny(line, 0, PARAMETER_SEPARATOR, VALUE_SEPARATOR);
        if (nameEnd < 0 || line.charAt(nameEnd) != PARAMETER_SEPARATOR || line.indexOf(BACKSLASH) < 0) {
            return line;
        }
        return rewriteParameters(line, nameEnd).orElse(line);
    }

    private static Optional<String> rewriteParameters(String line, int nameEnd) {
        StringBuilder rewritten = new StringBuilder(line.length() + 8).append(line, 0, nameEnd);
        int position = nameEnd;
        while (position < line.length() && line.charAt(position) == PARAMETER_SEPARATOR) {
            int nameSeparator = indexOfAny(line, position + 1, NAME_SEPARATOR, PARAMETER_SEPARATOR, VALUE_SEPARATOR);
            if (nameSeparator < 0 || line.charAt(nameSeparator) != NAME_SEPARATOR) {
                return Optional.empty();
            }
            rewritten.append(line, position, nameSeparator + 1);
            Optional<Integer> valueEnd = appendValue(line, nameSeparator + 1, rewritten);
            if (valueEnd.isEmpty()) {
                return Optional.empty();
            }
            position = valueEnd.get();
        }
        if (position >= line.length() || line.charAt(position) != VALUE_SEPARATOR) {
            return Optional.empty();
        }
        return Optional.of(rewritten.append(line, position, line.length()).toString());
    }

    /**
     * Appends the parameter value starting at the given position, and returns the position following it, or empty
     * when the value does not follow the RFC 5545 grammar.
     */
    private static Optional<Integer> appendValue(String line, int start, StringBuilder rewritten) {
        if (start < line.length() && line.charAt(start) == QUOTE) {
            int closingQuote = line.indexOf(QUOTE, start + 1);
            if (closingQuote < 0) {
                return Optional.empty();
            }
            appendQuoted(line.substring(start + 1, closingQuote), rewritten);
            return Optional.of(closingQuote + 1);
        }
        int end = indexOfAny(line, start, PARAMETER_SEPARATOR, VALUE_SEPARATOR);
        if (end < 0) {
            return Optional.empty();
        }
        String value = line.substring(start, end);
        if (value.indexOf(QUOTE) >= 0) {
            return Optional.empty();
        }
        if (value.indexOf(BACKSLASH) >= 0) {
            appendQuoted(value, rewritten);
        } else {
            rewritten.append(value);
        }
        return Optional.of(end);
    }

    private static void appendQuoted(String value, StringBuilder rewritten) {
        rewritten.append(QUOTE)
            .append(value.replace(String.valueOf(BACKSLASH), String.valueOf(BACKSLASH) + BACKSLASH))
            .append(QUOTE);
    }

    private static int indexOfAny(String line, int from, char... characters) {
        for (int i = from; i < line.length(); i++) {
            char current = line.charAt(i);
            for (char character : characters) {
                if (current == character) {
                    return i;
                }
            }
        }
        return -1;
    }

    private ParameterBackslashes() {
    }
}
