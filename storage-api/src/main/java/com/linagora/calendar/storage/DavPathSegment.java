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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

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
    private static final Pattern PCHARS = Pattern.compile("(?:[A-Za-z0-9\\-._~!$&'()*+,;=:@]|%[0-9A-Fa-f]{2})+");
    private static final Pattern DECODING_UNIT = Pattern.compile("%([0-9A-Fa-f]{2})|[^%]");
    private static final Set<String> DOT_SEGMENTS = Set.of(".", "..");
    private static final String FORBIDDEN_DECODED_CHARS = "/\\";

    public static String validate(String value, String name) {
        Preconditions.checkArgument(value != null && PCHARS.matcher(value).matches(),
            "%s must be a single path segment: %s", name, value);
        Preconditions.checkArgument(!DOT_SEGMENTS.contains(value),
            "%s must not be a dot segment: %s", name, value);
        validateDecoded(percentDecode(value), value, name);
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

    private static String percentDecode(String value) {
        byte[] bytes = DECODING_UNIT.matcher(value).results()
            .mapToInt(DavPathSegment::decodeUnit)
            .collect(ByteArrayOutputStream::new, ByteArrayOutputStream::write, (left, right) -> left.writeBytes(right.toByteArray()))
            .toByteArray();
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Path segment must decode to valid UTF-8: " + value, e);
        }
    }

    private static int decodeUnit(MatchResult unit) {
        return unit.group(1) == null ? unit.group().charAt(0) : Integer.parseInt(unit.group(1), 16);
    }

    private DavPathSegment() {
    }
}
