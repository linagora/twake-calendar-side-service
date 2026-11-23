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

import java.util.Optional;
import java.util.regex.Pattern;

import com.google.common.base.CharMatcher;

/**
 * Cleans text that does not come from an ICS before ical4j writes it in a TEXT value.
 *
 * <p>ical4j escapes LF in TEXT values but writes a lone CR verbatim, and parsers (ical4j's own included) read it as a
 * line break: free text would otherwise inject properties or components into the event.
 */
public final class IcsText {
    private static final CharMatcher TEXT_FORBIDDEN_CHARACTERS = CharMatcher.javaIsoControl().and(CharMatcher.noneOf("\n\t"));
    private static final Pattern UNPAIRED_SURROGATE = Pattern.compile("\\p{Cs}");

    /**
     * Line breaks become LF, which ical4j escapes; other control characters but HTAB are dropped, as are unpaired
     * surrogates.
     */
    public static Optional<String> sanitize(String value) {
        return Optional.ofNullable(value)
            .map(text -> text.replace("\r\n", "\n").replace('\r', '\n'))
            .map(TEXT_FORBIDDEN_CHARACTERS::removeFrom)
            .map(IcsText::withoutUnpairedSurrogates);
    }

    /**
     * For single line values, such as a UID: every control character is dropped, as are unpaired surrogates.
     */
    public static Optional<String> sanitizeSingleLine(String value) {
        return Optional.ofNullable(value)
            .map(CharMatcher.javaIsoControl()::removeFrom)
            .map(IcsText::withoutUnpairedSurrogates);
    }

    /**
     * UTF-8 cannot encode half of a surrogate pair: {@link String#getBytes} would silently write it as '?'.
     * Regular expressions match by code point, so that a valid pair is left alone.
     */
    public static String withoutUnpairedSurrogates(String value) {
        return UNPAIRED_SURROGATE.matcher(value).replaceAll("");
    }

    private IcsText() {
    }
}
