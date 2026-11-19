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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import com.google.common.base.Preconditions;

/**
 * Ensures an id designates exactly one DAV path segment: RFC 3986 'pchar' characters only
 * (well formed percent-encoded triplets allowed), and neither '.' nor '..'.
 * Checks apply to the percent-decoded value too, as the DAV server decodes the path once: an encoded
 * separator, dot segment or control character is rejected.
 * Encoded '?' and '#' remain accepted: the DAV server decodes the path after isolating it from the query
 * and the fragment, so they stay literal characters of a single segment.
 */
public final class DavPathSegment {
    private static final String UNENCODED_PCHARS = "-._~!$&'()*+,;=:@";
    private static final Set<String> DOT_SEGMENTS = Set.of(".", "..");
    private static final String FORBIDDEN_DECODED_CHARS = "/\\";

    public static String validate(String value, String name) {
        Optional<String> decoded = Optional.ofNullable(value).flatMap(DavPathSegment::percentDecodePchars);
        Preconditions.checkArgument(decoded.isPresent(),
            "%s must be a single path segment: %s", name, value);
        Preconditions.checkArgument(!DOT_SEGMENTS.contains(value),
            "%s must not be a dot segment: %s", name, value);
        if (value.indexOf('%') >= 0) {
            // without percent-encoding, the decoded value is the value: pchar characters, already checked
            validateDecoded(decoded.get(), value, name);
        }
        return value;
    }

    public static boolean isDotSegment(String value) {
        return DOT_SEGMENTS.contains(value);
    }

    private static void validateDecoded(String decoded, String value, String name) {
        Preconditions.checkArgument(!DOT_SEGMENTS.contains(decoded),
            "%s must not decode to a dot segment: %s", name, value);
        Preconditions.checkArgument(decoded.chars().noneMatch(DavPathSegment::isForbiddenDecodedChar),
            "%s must not decode to several path segments or control characters: %s", name, value);
    }

    private static boolean isForbiddenDecodedChar(int c) {
        return Character.isISOControl(c) || FORBIDDEN_DECODED_CHARS.indexOf(c) >= 0;
    }

    /**
     * Decodes a non-empty sequence of RFC 3986 'pchar': unreserved and sub-delims characters, ':', '@' and
     * well formed percent-encoded triplets. Empty when the value is not such a sequence.
     *
     * @throws IllegalArgumentException when the decoded bytes are not valid UTF-8
     */
    private static Optional<String> percentDecodePchars(String value) {
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.indexOf('%') < 0) {
            return value.chars().allMatch(DavPathSegment::isUnencodedPchar) ? Optional.of(value) : Optional.empty();
        }
        byte[] bytes = new byte[value.length()];
        int length = 0;
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    return Optional.empty();
                }
                int high = hexDigit(value.charAt(i + 1));
                int low = hexDigit(value.charAt(i + 2));
                if (high < 0 || low < 0) {
                    return Optional.empty();
                }
                bytes[length++] = (byte) (high << 4 | low);
                i += 3;
            } else if (isUnencodedPchar(c)) {
                bytes[length++] = (byte) c;
                i++;
            } else {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 0, length)).toString());
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Path segment must decode to valid UTF-8: " + value, e);
        }
    }

    private static boolean isUnencodedPchar(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
            || UNENCODED_PCHARS.indexOf(c) >= 0;
    }

    private static int hexDigit(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    private DavPathSegment() {
    }
}
