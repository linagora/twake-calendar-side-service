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

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.UnicodeSet;
import com.ibm.icu.util.ULocale;
import com.mongodb.client.model.Collation;
import com.mongodb.client.model.CollationStrength;
import com.mongodb.client.model.Filters;

/**
 * Prefix search ignoring case, accents and the normalisation form of stored names, relying on the Unicode collation of
 * MongoDB: 'theo' finds 'Théo', 'ijs' finds 'Ĳsbrand' and 'रम' does not find 'रीमा'.
 *
 * <p>MongoDB regexes ignore collations, hence a range: U+FFFF sorts after any other character in the root collation,
 * so that [query, query + U+FFFF[ holds the strings starting with the query.</p>
 *
 * <p>Known limits of the primary strength of the root collation:
 * <ul>
 *     <li>Letters having their own primary weight are not folded: 'isik' does not find 'Işık', nor does 'tor' find 'Ŧor',
 *     'ia' find 'ɨa', 'thor' find 'Þór' or 'احمد' find 'أحمد'.</li>
 *     <li>A nukta is ignored: 'कख' finds 'क़ख'.</li>
 *     <li>Thai and Lao vowels written before their consonant sort after it: 'ก' finds 'เกียรติ'.</li>
 * </ul></p>
 */
final class CollatedPrefixSearch {
    static final Collation COLLATION = Collation.builder()
        .locale("en")
        .collationStrength(CollationStrength.PRIMARY)
        .build();

    private static final String AFTER_ANY_CHARACTER = "￿";
    private static final Collator PRIMARY_COLLATOR = primaryCollator();
    private static final UnicodeSet VOWELS_SORTED_AFTER_THEIR_CONSONANT = new UnicodeSet("[:Logical_Order_Exception:]").freeze();

    private static Collator primaryCollator() {
        Collator collator = Collator.getInstance(ULocale.ENGLISH);
        collator.setStrength(Collator.PRIMARY);
        return collator.freeze();
    }

    /**
     * A query only made of what the collation ignores (marks, zero width characters, controls...) would otherwise find
     * every name.
     */
    static boolean isSearchable(String query) {
        return query.isEmpty() || PRIMARY_COLLATOR.compare(query, "") != 0;
    }

    /**
     * @param arrayFields need $elemMatch: both bounds of a range could otherwise be met by distinct values of the array
     */
    static Bson filter(String query, List<String> scalarFields, List<String> arrayFields) {
        Document range = new Document("$gte", query).append("$lt", query + AFTER_ANY_CHARACTER);
        Stream<Bson> ranges = Stream.concat(
            scalarFields.stream().map(field -> new Document(field, range)),
            arrayFields.stream().map(field -> new Document(field, new Document("$elemMatch", range))));
        if (endsWithAVowelSortedAfterItsConsonant(query)) {
            // The range misses this vowel as long as its consonant is not typed
            String literalPrefix = "^" + Pattern.quote(withoutWhatARegexCanNotHold(query));
            ranges = Stream.concat(ranges, Stream.concat(scalarFields.stream(), arrayFields.stream())
                .map(field -> Filters.regex(field, literalPrefix)));
        }
        return Filters.or(ranges.toList());
    }

    private static boolean endsWithAVowelSortedAfterItsConsonant(String query) {
        return !query.isEmpty() && VOWELS_SORTED_AFTER_THEIR_CONSONANT.contains(query.codePointBefore(query.length()));
    }

    /**
     * MongoDB rejects a regex holding NUL or an unpaired surrogate, whereas a range accepts them.
     */
    private static String withoutWhatARegexCanNotHold(String query) {
        return query.codePoints()
            .filter(codePoint -> codePoint != 0 && Character.getType(codePoint) != Character.SURROGATE)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();
    }

    private CollatedPrefixSearch() {
    }
}
