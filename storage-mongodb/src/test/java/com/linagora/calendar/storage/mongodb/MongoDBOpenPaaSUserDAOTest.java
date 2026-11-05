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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.assertj.core.api.SoftAssertions;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.MigrationResult;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAOContract;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBOpenPaaSUserDAOTest implements OpenPaaSUserDAOContract {
    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension();

    private MongoDBOpenPaaSUserDAO mongoDBOpenPaaSUserDAO;

    @BeforeEach
    void setUp() {
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        domainDAO.add(USERNAME.getDomainPart().get()).block();
        domainDAO.add(USERNAME_2.getDomainPart().get()).block();
        mongoDBOpenPaaSUserDAO = new MongoDBOpenPaaSUserDAO(mongo.getDb(), domainDAO);
    }

    @Override
    public OpenPaaSUserDAO testee() {
        return mongoDBOpenPaaSUserDAO;
    }

    private static final Domain DOMAIN = Domain.of("domain.tld");

    private List<OpenPaaSUser> search(String query) {
        return testee().search(DOMAIN, query, 10).collectList().block();
    }

    @ParameterizedTest
    @CsvSource({
        "dang, \u0110\u1EB7ng", // the regression of a regex built from what decomposes
        "duc, \u0110\u1EE9c",
        "\u0110\u1EB7ng, \u0110\u1EB7ng",
        "lukasz, \u0141ukasz",
        "\u0141ukasz, \u0141ukasz",
        "soren, S\u00F8ren",
        "oeuvre, \u0152uvre",
        "strasse, Stra\u00DFe",
        "IJsbrand, \u0132sbrand",
        "i, \u0132sbrand",
        "\u0133s, IJsbrand",
        "fix, \uFB01x",
        "\uFB01x, fix",
        "offi, o\uFB03",
        "off, of\uFB01",
        "dzo, \u01C6o",
        "\u01C4o, dzo",
        "anna, \uFF21nna", // full-width
        "hanh, \u02B0anh", // modifier
        "zoe, \u2128oe", // letterlike
        "ian, \u2160an", // roman numeral
        "anna, \u24B6nna", // circled
        "\uD835\uDC1A, a", // mathematical
        "H\u00E9l\u00E8ne, He\u0301le\u0300ne", // decomposed name
        "He\u0301le\u0300ne, H\u00E9l\u00E8ne", // decomposed query
        "Helene, He\u0301le\u0300ne",
        "Ad\u00E9b\u00E1y\u1ECD\u0300, Adebayo", // NFC can not compose this grave
        "\uC924, \u110C\u116F\u11B3", // Hangul
        "\u110C\u116F\u11B3, \uC924",
        "\u0438\u0306, \u0439", // Cyrillic
        "\u095E\u093E, \u092B\u093C\u093E", // composition exclusion
        "\uFA01, \u5EA6", // singleton
        "\u0645\u062D\u0645\u062F, \u0645\u064F\u062D\u064E\u0645\u0651\u064E\u062F", // Arabic pointing
        "\u0645\u064F\u062D\u064E\u0645\u0651\u064E\u062F, \u0645\u062D\u0645\u062F",
        "\u05E9\u05DC\u05D5\u05DD, \u05E9\u05C1\u05B8\u05DC\u05D5\u05B9\u05DD", // Hebrew pointing
        "\u845B\u98FE, \u845B\uDB40\uDD00\u98FE", // variation selector
        "a\u0951\u093Cb, a\u093C\u0951b", // canonical order of marks
        "\u0930\u0940\u092E\u093E, \u0930\u0940\u092E\u093E", // a vowel sign that is typed
        "\u03B1\u03B9, \u03B1\u03B9\u03B1", // iota
        "\u0E40, \u0E40\u0E01\u0E35\u0E22\u0E23\u0E15\u0E34", // Thai vowel written before its consonant
        "\u0E40\u0E01, \u0E40\u0E01\u0E35\u0E22\u0E23\u0E15\u0E34",
        "\u0E44\u0E1E\u0E42, \u0E44\u0E1E\u0E42\u0E23\u0E08\u0E19\u0E4C", // in the middle of a name
        "\u0EC0, \u0EC0\u0E9E\u0EB1\u0E94", // Lao
        "jean-p, Jean-Paul",
        "o'b, O'Brien"
    })
    void searchShouldFindPrefix(String query, String name) {
        OpenPaaSUser user = testee().add(USERNAME, name, "Lastname").block();

        assertThat(search(query)).containsExactly(user);
    }

    @ParameterizedTest
    @CsvSource({
        "\u0930\u092E, \u0930\u0940\u092E\u093E", // a Devanagari vowel sign is no accent
        "\u0E01\u0E19, \u0E01\u0E34\u0E19", // Thai
        "\u0BAE\u0BA9, \u0BAE\u0BBE\u0BA9", // Tamil
        "ab, a\u0940b",
        "\u0915\u093F, \u0915",
        "\uAE40\uBBFC, \uAE40\uC900",
        "\uC924, \u110C\u116F\u11B2",
        "\u03B1\u03B1, \u03B1\u03B9\u03B1",
        "\u0438, \u0439",
        "is, \u0132sbrand",
        "ix, \uFB01",
        "fo, \uFB01",
        "jeanp, Jean-Paul",
        "ob, O'Brien",
        "th.\u0E40, tha\u0E40" // quoted when a regex is needed
    })
    void searchShouldNotFindOtherNames(String query, String name) {
        testee().add(USERNAME, name, "Lastname").block();

        assertThat(search(query)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0301", "\u200B", "\u0000", "\u00AD", "\uFE0F", "\u05A0\u0301"})
    void searchShouldNotFindEveryoneWhenTheQueryHoldsOnlyWhatTheCollationIgnores(String query) {
        testee().add(USERNAME, "Jean", "Lastname").block();

        assertThat(search(query)).isEmpty();
    }

    @Test
    void searchShouldStillListUsersOnAnEmptyQuery() {
        OpenPaaSUser user = testee().add(USERNAME, "Jean", "Lastname").block();

        assertThat(search("")).containsExactly(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\u0000\u0E40", "\\E\u0E40", "\uD800", "\uD800\u0E40", "\uFFFF", "jean\uFFFF", "\u0E40\uFFFF"})
    void searchShouldNotFailOnUnusualQueries(String query) {
        testee().add(USERNAME, "Jean", "Lastname").block();

        assertThat(search(query)).isEmpty();
    }

    @Test
    void searchShouldFindALiteralPrefixHoldingNulBeforeAVowelSortedAfterItsConsonant() {
        OpenPaaSUser user = testee().add(USERNAME, "\u0E40\u0E01", "Lastname").block();

        assertThat(search("\u0000\u0E40")).containsExactly(user);
    }

    @Test
    void searchShouldNotFailOnAVeryLongQuery() {
        testee().add(USERNAME, "Theo", "Lefevre").block();

        assertThat(search("theo".repeat(5000))).isEmpty();
    }

    @Test
    void searchShouldFindANameHoldingALongRunOfMarks() {
        OpenPaaSUser user = testee().add(USERNAME, "a" + "\u05A0".repeat(200) + "b", "Lefevre").block();

        assertThat(search("a" + "\u05A0".repeat(6) + "b")).containsExactly(user);
    }

    @Test
    void searchShouldNotMeetTheRangeWithDistinctFirstnames() {
        testee().add(Username.of("zed@domain.tld"), "Alain Zoe", "Zed").block();

        assertThat(search("m")).isEmpty();
    }

    @Test
    void searchShouldNotMeetTheRangeWithDistinctEmails() {
        OpenPaaSUser user = testee().add(USERNAME, "Bob", "Cole").block();
        Mono.from(mongo.getDb().getCollection(MongoDBOpenPaaSUserDAO.COLLECTION)
                .updateOne(Filters.eq("firstname", "Bob"), Updates.set("accounts", List.of(
                    new Document("emails", List.of(USERNAME.asString(), "zed@domain.tld")),
                    new Document("emails", List.of("alice@domain.tld"))))))
            .block();

        assertThat(search("m")).isEmpty();
        assertThat(search("zed")).containsExactly(user);
        assertThat(search("alice")).containsExactly(user);
    }

    @Test
    void searchIndexesShouldUseTheCollationOfTheSearch() {
        List<Document> indexes = Flux.from(mongo.getDb().getCollection(MongoDBOpenPaaSUserDAO.COLLECTION).listIndexes())
            .collectList()
            .block();

        assertThat(indexes)
            .filteredOn(index -> index.getString("name").startsWith(MongoDBCollectionFactory.SEARCH_INDEX_PREFIX))
            .hasSize(MongoDBCollectionFactory.SEARCH_INDEXED_USER_FIELDS.size())
            .allSatisfy(index -> {
                Document collation = index.get("collation", Document.class);
                assertThat(collation.getString("locale")).isEqualTo("en");
                assertThat(collation.getInteger("strength")).isEqualTo(1);
            });
    }

    @Test
    void addMissingFieldsShouldAddEmailAndFirstnamesWhenMissing() {
        // Given a document with missing fields
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        ObjectId domainId = new ObjectId(domainDAO.retrieve(USERNAME.getDomainPart().get()).block().id().value());

        Document userWithoutFields = new Document()
            .append("firstname", "Jean Paul")
            .append("lastname", "Dupont")
            .append("password", "secret")
            .append("domains", List.of(new Document("domain_id", domainId)))
            .append("accounts", List.of(new Document()
                .append("type", "email")
                .append("emails", List.of(USERNAME.asString()))));

        ObjectId userId = Mono.from(mongo.getDb().getCollection("users").insertOne(userWithoutFields))
            .map(result -> result.getInsertedId().asObjectId().getValue())
            .block();

        // When is is migrated
        MigrationResult result = mongoDBOpenPaaSUserDAO.addMissingFields().block();
        Document updatedUser = Mono.from(mongo.getDb().getCollection("users")
                .find(new Document("_id", userId))
                .first())
            .block();

        SoftAssertions.assertSoftly(softly -> {
            // Then it is reported in migration results
            softly.assertThat(result.processedUsers()).isEqualTo(1);
            softly.assertThat(result.upgradedUsers()).isEqualTo(1);
            softly.assertThat(result.errorCount()).isEqualTo(0);

            // And the entry is correctly updated
            softly.assertThat(updatedUser.getString("email")).isEqualTo(USERNAME.asString());
            softly.assertThat(updatedUser.getList("firstnames", String.class)).containsExactly("Jean", "Paul");
        });
    }
}
