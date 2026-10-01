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

package com.linagora.calendar.storage.mongodb;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;

/**
 * MongoDB regexes can not ignore diacritics. This builds a prefix regex in which every latin letter
 * is replaced by a character class holding all its accented variants, so that 'theo' matches 'Théo'
 * (and the other way around), aligning with the ASCII folding performed by the OpenSearch contact
 * auto-complete.
 */
public class AccentInsensitivePrefixPattern {
    private static final int LATIN_RANGE_START = 'A';
    private static final int LATIN_RANGE_END = 0x024F;

    private static final Map<String, String> CHARACTER_CLASS_BY_FOLDED_LETTER = IntStream.rangeClosed(LATIN_RANGE_START, LATIN_RANGE_END)
        .filter(Character::isLetter)
        .mapToObj(Character::toString)
        .filter(letter -> isAsciiLetter(fold(letter)))
        .collect(Collectors.groupingBy(AccentInsensitivePrefixPattern::fold, Collectors.joining()))
        .entrySet()
        .stream()
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> "[" + entry.getValue() + "]"));

    public static Pattern of(String query) {
        String regex = fold(query)
            .codePoints()
            .mapToObj(Character::toString)
            .map(AccentInsensitivePrefixPattern::toRegex)
            .collect(Collectors.joining("", "^", ""));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static String toRegex(String character) {
        return CHARACTER_CLASS_BY_FOLDED_LETTER.getOrDefault(character, Pattern.quote(character));
    }

    private static String fold(String value) {
        return StringUtils.stripAccents(value).toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiLetter(String value) {
        return value.length() == 1 && value.charAt(0) >= 'a' && value.charAt(0) <= 'z';
    }
}
