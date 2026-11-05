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

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;

/**
 * MongoDB regexes can not ignore diacritics. This builds a prefix regex in which every latin letter
 * is replaced by a character class holding all its accented variants, so that 'theo' matches 'Théo'
 * (and the other way around), aligning with the ASCII folding performed by the OpenSearch contact
 * auto-complete.
 *
 * <p>The query is split into clusters: a character followed by its combining marks and by the Hangul
 * vowel and final jamo it may compose with. A cluster whose base is not a latin letter (Hangul, Cyrillic,
 * Devanagari, ...) is matched as typed, in NFC or in NFD: neither MongoDB nor java regexes consider
 * canonically equivalent sequences equal, and the stored names are not normalised. Each cluster may be
 * followed by combining marks so that names stored decomposed (NFD) are found.</p>
 */
public class AccentInsensitivePrefixPattern {
    private static final int LATIN_RANGE_START = 'A';
    private static final int LATIN_RANGE_END = 0x024F;
    private static final int LATIN_EXTENDED_ADDITIONAL_START = 0x1E00;
    private static final int LATIN_EXTENDED_ADDITIONAL_END = 0x1EFF;
    private static final int HANGUL_COMPOSABLE_VOWEL_JAMO_START = 0x1161;
    private static final int HANGUL_COMPOSABLE_VOWEL_JAMO_END = 0x1175;
    private static final int HANGUL_COMPOSABLE_FINAL_JAMO_START = 0x11A8;
    private static final int HANGUL_COMPOSABLE_FINAL_JAMO_END = 0x11C2;
    private static final String OPTIONAL_COMBINING_MARKS = "\\p{M}*";

    private static final Map<String, String> LETTERS_BY_FOLDED_LETTER = IntStream.concat(
            IntStream.rangeClosed(LATIN_RANGE_START, LATIN_RANGE_END),
            IntStream.rangeClosed(LATIN_EXTENDED_ADDITIONAL_START, LATIN_EXTENDED_ADDITIONAL_END))
        .filter(Character::isLetter)
        .mapToObj(Character::toString)
        .filter(letter -> isAsciiLetter(fold(letter)))
        .collect(Collectors.groupingBy(AccentInsensitivePrefixPattern::fold, Collectors.joining()))
        .entrySet()
        .stream()
        .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));

    public static Pattern of(String query) {
        String regex = clusters(query).stream()
            .map(AccentInsensitivePrefixPattern::toRegex)
            .collect(Collectors.joining("", "^", ""));
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private static List<List<String>> clusters(String query) {
        List<Integer> codePoints = query.codePoints().boxed().toList();
        int[] clusterStarts = IntStream.range(0, codePoints.size())
            .filter(index -> index == 0 || !extendsCluster(codePoints.get(index)))
            .toArray();
        return IntStream.range(0, clusterStarts.length)
            .mapToObj(cluster -> codePoints.subList(clusterStarts[cluster],
                cluster + 1 < clusterStarts.length ? clusterStarts[cluster + 1] : codePoints.size()))
            .map(cluster -> cluster.stream().map(Character::toString).toList())
            .toList();
    }

    private static boolean extendsCluster(int codePoint) {
        return isCombiningMark(codePoint)
            || isInRange(codePoint, HANGUL_COMPOSABLE_VOWEL_JAMO_START, HANGUL_COMPOSABLE_VOWEL_JAMO_END)
            || isInRange(codePoint, HANGUL_COMPOSABLE_FINAL_JAMO_START, HANGUL_COMPOSABLE_FINAL_JAMO_END);
    }

    private static boolean isCombiningMark(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
            || type == Character.ENCLOSING_MARK
            || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean isInLatinRanges(int codePoint) {
        return isInRange(codePoint, LATIN_RANGE_START, LATIN_RANGE_END)
            || isInRange(codePoint, LATIN_EXTENDED_ADDITIONAL_START, LATIN_EXTENDED_ADDITIONAL_END);
    }

    private static boolean isInRange(int codePoint, int start, int end) {
        return codePoint >= start && codePoint <= end;
    }

    private static String toRegex(List<String> cluster) {
        String base = cluster.getFirst();
        if (isLatinLetter(base)) {
            return latinClusterRegex(base, cluster.subList(1, cluster.size()));
        }
        return canonicalFormsRegex(String.join("", cluster));
    }

    /**
     * Diacritics following a latin letter (eg the grave of 'ọ̀', that NFC can not compose) are already accepted
     * by the optional combining marks following its class: requiring them would miss the name stored without accents.
     */
    private static String latinClusterRegex(String letter, List<String> marks) {
        return Stream.concat(
                Stream.of(letterRegex(letter)),
                marks.stream()
                    .filter(mark -> !isStrippedMark(mark))
                    .map(Pattern::quote))
            .map(regex -> regex + OPTIONAL_COMBINING_MARKS)
            .collect(Collectors.joining());
    }

    /**
     * Compatibility letters outside the latin ranges (eg full-width 'Ａ' U+FF21, modifier 'ʰ' U+02B0, letterlike 'ℨ' U+2128)
     * fold to an ascii letter but are not part of its class: they are added to it so that a name is found when typed
     * exactly as stored. They go inside the class rather than in an alternation: some of them (eg the Kelvin sign
     * U+212A) are already matched case-insensitively by the class, and an alternation with two branches matching the
     * same character backtracks exponentially when the rest of the regex fails.
     */
    private static String letterRegex(String letter) {
        String letters = LETTERS_BY_FOLDED_LETTER.get(fold(letter));
        if (isInLatinRanges(letter.codePointAt(0))) {
            return "[" + letters + "]";
        }
        return "[" + letters + letter + "]";
    }

    /**
     * Composition exclusions (eg 'फ़' U+095E), singletons (eg U+FA01) and Hangul have an NFC form that differs
     * from the typed one, and a name may be stored in any of them.
     */
    private static String canonicalFormsRegex(String cluster) {
        return Stream.of(cluster, Normalizer.normalize(cluster, Normalizer.Form.NFC), Normalizer.normalize(cluster, Normalizer.Form.NFD))
            .distinct()
            .map(Pattern::quote)
            .collect(Collectors.joining("|", "(?:", ")")) + OPTIONAL_COMBINING_MARKS;
    }

    private static boolean isStrippedMark(String character) {
        return fold(character).isEmpty();
    }

    private static boolean isLatinLetter(String character) {
        return LETTERS_BY_FOLDED_LETTER.containsKey(fold(character));
    }

    private static String fold(String value) {
        return StringUtils.stripAccents(value).toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiLetter(String value) {
        return value.length() == 1 && value.charAt(0) >= 'a' && value.charAt(0) <= 'z';
    }
}
