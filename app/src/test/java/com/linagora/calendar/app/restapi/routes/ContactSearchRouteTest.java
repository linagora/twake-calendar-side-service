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

package com.linagora.calendar.app.restapi.routes;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static io.restassured.RestAssured.given;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.newConfig;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_OK;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.dav.DavRight;
import com.linagora.calendar.dav.HttpUtils;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.restapi.RestApiServerProbe;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

import io.restassured.RestAssured;
import io.restassured.authentication.PreemptiveBasicAuthScheme;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;

class ContactSearchRouteTest {
    private static final String PASSWORD = "secret";
    private static final String ROUTE = "/contacts/api/contacts/search";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @RegisterExtension
    @Order(1)
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MONGODB),
        AppTestHelper.OIDC_BY_PASS_MODULE,
        DavModuleTestHelper.FROM_SABRE_EXTENSION.apply(sabreDavExtension));

    @AfterAll
    static void afterAll() {
        RestAssured.reset();
    }

    private OpenPaaSUser requester;
    private CardDavClient cardDavClient;
    private CalendarDataProbe calendarDataProbe;

    @BeforeEach
    void setUp(TwakeCalendarGuiceServer server) throws Exception {
        requester = sabreDavExtension.newTestUser();
        calendarDataProbe = server.getProbe(CalendarDataProbe.class);
        calendarDataProbe.addDomain(requester.username().getDomainPart().get());
        calendarDataProbe.addUserToRepository(requester.username(), PASSWORD);

        PreemptiveBasicAuthScheme auth = new PreemptiveBasicAuthScheme();
        auth.setUserName(requester.username().asString());
        auth.setPassword(PASSWORD);
        RestAssured.requestSpecification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setConfig(newConfig().encoderConfig(encoderConfig().defaultContentCharset(StandardCharsets.UTF_8)))
            .setPort(server.getProbe(RestApiServerProbe.class).getPort().getValue())
            .setBasePath("")
            .setAuth(auth)
            .build();
        cardDavClient = new CardDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @Test
    void shouldMergeBooksInRequestOrderAndPaginateGlobally() {
        // Given contacts in two books, including a non-match and a repeated book in the request
        createContact(requester, "contacts", "b", "Search 1061 Bob");
        createContact(requester, "contacts", "a", "Search 1061 Alice");
        createContact(requester, "contacts", "x", "Other Person");
        createContact(requester, "collected", "c", "Search 1061 Collected");

        // When offset skips the first match across the combined result
        String response = given()
            .queryParam("limit", 2)
            .queryParam("offset", 1)
            .body("""
                {
                  "query": "Search 1061",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" },
                    { "userId": "{userId}", "addressBookId": "contacts" },
                    { "userId": "{userId}", "addressBookId": "collected" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the remaining contacts keep book order and URI order within each book
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/b.vcf",
                  "/addressbooks/{userId}/collected/c.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
    }

    @Test
    void shouldKeepDuplicateContactsFromDifferentAddressBooks() {
        // Given two address books contain contacts with the same UID and full name
        createContact(requester, "contacts", "duplicate", "grepme Duplicate Contact");
        createContact(requester, "collected", "duplicate", "grepme Duplicate Contact");

        // When searching both address books
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" },
                    { "userId": "{userId}", "addressBookId": "collected" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then both DAV resources are kept because contacts are not deduplicated across books
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/duplicate.vcf",
                  "/addressbooks/{userId}/collected/duplicate.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
    }

    @Test
    void shouldReturnEmptyListWhenNoAddressBooksAreRequested() {
        // Given a request without address books
        // When searching contacts
        String response = given()
            .body("{}")
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the result contains an empty DAV item list
        assertThatJson(response).isEqualTo("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("queryFieldsSearchingAllContacts")
    void shouldSearchAllContactsWhenQueryIsMissingNullOrEmpty(String scenario, String queryField) {
        // Given a readable address book containing a contact and a missing, null, or empty query
        createContact(requester, "contacts", "all-contacts", "Contact Returned Without Query");

        // When searching the address book
        String response = given()
            .body("""
                {
                  {queryField}
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{queryField}", queryField)
                .replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then an empty effective query returns every contact in the requested book
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/all-contacts.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
    }

    @Test
    void shouldIgnoreUnreadableAddressBookAndReturnReadableResults() {
        // Given the caller can read the first book but cannot read the second
        createContact(requester, "contacts", "a", "Search 1061 Alice");
        OpenPaaSUser owner = sabreDavExtension.newTestUser();

        // When searching the readable and unreadable books together
        String response = given()
            .queryParam("limit", 1)
            .body("""
                {
                  "query": "Search 1061",
                  "addressBooks": [
                    { "userId": "{requesterId}", "addressBookId": "contacts" },
                    { "userId": "{ownerId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{requesterId}", requester.id().value())
                .replace("{ownerId}", owner.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the unreadable book is ignored and the readable result is returned
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/a.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
    }

    @Test
    void shouldIgnoreMissingAddressBook() {
        // Given a valid reference to an address book that does not exist
        // When searching the missing address book
        String response = given()
            .body("""
                {
                  "query": "",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "missing-address-book" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the missing book is ignored
        assertThatJson(response).isEqualTo("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);
    }

    @Test
    void shouldRespectExplicitLimit() {
        // Given ten contacts matching the keyword
        IntStream.range(0, 10)
            .forEach(index -> createContact(requester, "contacts", "contact-%02d".formatted(index), "grepme " + index));

        // When the caller requests only three results
        String response = given()
            .queryParam("limit", 3)
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then exactly the first three URI-sorted contacts are returned
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/contact-00.vcf",
                  "/addressbooks/{userId}/contacts/contact-01.vcf",
                  "/addressbooks/{userId}/contacts/contact-02.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
    }

    @Test
    void shouldDefaultLimitToThirty() {
        // Given more matching contacts than the default page size
        IntStream.range(0, 31)
            .forEach(index -> createContact(requester, "contacts", "contact-%02d".formatted(index), "grepme " + index));

        // When the caller omits limit
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the default page contains 30 contacts, starting with the first URI
        assertThatJson(response)
            .inPath("_embedded.dav:item")
            .isArray()
            .hasSize(30);
        assertThatJson(response)
            .inPath("_embedded.dav:item[0]._links.self.href")
            .isEqualTo("/addressbooks/" + requester.id().value() + "/contacts/contact-00.vcf");
        assertThatJson(response)
            .inPath("_embedded.dav:item[29]._links.self.href")
            .isEqualTo("/addressbooks/" + requester.id().value() + "/contacts/contact-29.vcf");
    }

    @Test
    void shouldUseZeroBasedOffsetForPagination() {
        // Given four matching contacts sorted by URI
        IntStream.range(0, 4)
            .forEach(index -> createContact(requester, "contacts", "contact-%02d".formatted(index), "grepme " + index));

        // When requesting the first page, the second page, and a page past the end
        String firstPage = given()
            .queryParam("limit", 2)
            .queryParam("offset", 0)
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();
        String secondPage = given()
            .queryParam("limit", 2)
            .queryParam("offset", 2)
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();
        String pastEnd = given()
            .queryParam("limit", 2)
            .queryParam("offset", 4)
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then offset counts matching contacts from zero, not page numbers
        assertThatJson(firstPage)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/contact-00.vcf",
                  "/addressbooks/{userId}/contacts/contact-01.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
        assertThatJson(secondPage)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/contact-02.vcf",
                  "/addressbooks/{userId}/contacts/contact-03.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
        assertThatJson(pastEnd).isEqualTo("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);
    }

    @Test
    void shouldFilterByKeywordIgnoringCase() {
        // Given two matching contacts with different letter cases and one unrelated contact
        createContact(requester, "contacts", "a", "GrepMe Alice");
        createContact(requester, "contacts", "b", "grepme Bob");
        createContact(requester, "contacts", "c", "Unrelated Carol");

        // When searching for the lower-case keyword
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then only contacts whose vCard contains the keyword are returned
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/a.vcf",
                  "/addressbooks/{userId}/contacts/b.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
        assertThat(response.toLowerCase())
            .contains("grepme alice", "grepme bob")
            .doesNotContain("unrelated carol");
    }

    @Test
    void shouldEncodeSearchQueryContainingPlusAmpersandAndUnicode() {
        // Given a contact matching a regex containing URI-sensitive and Unicode characters
        createContact(requester, "contacts", "encoded-query", "AAAB & Nguyễn");

        // When searching with plus, ampersand, and Unicode characters
        String response = given()
            .body("""
                {
                  "query": "A+B & Nguyễn",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the complete query reaches DAV without being split or decoded as a space
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{userId}/contacts/encoded-query.vcf"
                ]
                """.replace("{userId}", requester.id().value()));
        assertThat(response).contains("AAAB & Nguyễn");
    }

    @Test
    void shouldIgnorePrivateAddressBook() {
        // Given Alice owns a private contact
        OpenPaaSUser alice = sabreDavExtension.newTestUser();
        createContact(alice, "contacts", "alice", "grepme Alice");

        // When Bob searches Alice's private address book
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", alice.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the private book is ignored without exposing its contact
        assertThatJson(response).isEqualTo("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);
    }

    @Test
    void shouldSearchSharedAddressBookUsingCanonicalUrl() {
        // Given Alice shares her contacts with Bob using read rights
        OpenPaaSUser alice = sabreDavExtension.newTestUser();
        createContact(alice, "contacts", "alice", "grepme Alice Shared Contact");
        cardDavClient.updateAddressBookShares(alice.username(), new AddressBookURL(alice.id(), "contacts"),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + requester.username().asString(), DavRight.READ.access()))).block();

        // When Bob searches Alice's canonical address book URL
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", alice.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the shared contact is returned
        assertThatJson(response)
            .inPath("_embedded.dav:item")
            .isArray()
            .hasSize(1);
        assertThat(response).contains("grepme Alice Shared Contact");
    }

    @Test
    void shouldSearchDelegatedAddressBookUsingMirrorUrl() {
        // Given Alice delegates read access to her contacts to Bob
        OpenPaaSUser alice = sabreDavExtension.newTestUser();
        createContact(alice, "contacts", "alice", "grepme Alice Delegated Contact");
        cardDavClient.updateAddressBookShares(alice.username(), new AddressBookURL(alice.id(), "contacts"),
            List.of(new CardDavClient.AddressBookSharee("mailto:" + requester.username().asString(), DavRight.READ.access()))).block();
        String mirrorId = cardDavClient.listUserAddressBookIds(requester.username(), requester.id())
            .map(CardDavClient.AddressBook::value)
            .filter(id -> !List.of("contacts", "collected").contains(id))
            .blockFirst();
        assertThat(mirrorId).isNotNull();

        // When Bob searches the delegated address book through its mirror URL
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "{addressBookId}" }
                  ]
                }
                """.replace("{userId}", requester.id().value())
                .replace("{addressBookId}", mirrorId))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the delegated contact is returned
        assertThatJson(response)
            .inPath("_embedded.dav:item")
            .isArray()
            .hasSize(1);
        assertThat(response).contains("grepme Alice Delegated Contact");
    }

    @Test
    void shouldSearchDomainMembersAddressBookFromSameDomain() {
        // Given the requester's domain-members address book contains a matching contact
        Domain domain = requester.username().getDomainPart().orElseThrow();
        OpenPaaSId domainId = calendarDataProbe.domainId(domain);
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:domain-member
            FN:grepme Domain Member
            END:VCARD
            """;
        cardDavClient.createDomainMembersAddressBook(domainId).block();
        cardDavClient.upsertContactDomainMembers(domainId, "domain-member", vcard.getBytes(StandardCharsets.UTF_8)).block();

        // When the requester searches the address book belonging to their domain
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "domain-members" }
                  ]
                }
                """.replace("{userId}", domainId.value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the domain member is returned
        assertThatJson(response)
            .inPath("_embedded.dav:item[*]._links.self.href")
            .isEqualTo("""
                [
                  "/addressbooks/{domainId}/domain-members/domain-member.vcf"
                ]
                """.replace("{domainId}", domainId.value()));
        assertThat(response).contains("grepme Domain Member");
    }

    @Test
    void shouldIgnoreDomainMembersAddressBookFromAnotherDomain() {
        // Given another domain owns a domain-members address book containing a matching contact
        OpenPaaSDomain foreignDomain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of("foreign-" + UUID.randomUUID() + ".tld"))
            .block();
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:foreign-domain-member
            FN:grepme Foreign Domain Member
            END:VCARD
            """;
        cardDavClient.createDomainMembersAddressBook(foreignDomain.id()).block();
        cardDavClient.upsertContactDomainMembers(foreignDomain.id(), "foreign-domain-member", vcard.getBytes(StandardCharsets.UTF_8)).block();

        // When the requester searches an address book belonging to another domain
        String response = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "domain-members" }
                  ]
                }
                """.replace("{userId}", foreignDomain.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();

        // Then the foreign domain book is ignored without exposing its contact
        assertThatJson(response).isEqualTo("""
            {
              "_embedded": {
                "dav:item": []
              }
            }
            """);
    }

    @Test
    void shouldReturnHrefThatCanFetchContactDetail() throws Exception {
        // Given a contact that can be found by search
        createContact(requester, "contacts", "detail", "grepme Detail Contact");

        // When searching and then GETting the href returned by DAV
        String searchResponse = given()
            .body("""
                {
                  "query": "grepme",
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts" }
                  ]
                }
                """.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_OK)
            .extract()
            .body()
            .asString();
        JsonNode item = OBJECT_MAPPER.readTree(searchResponse).path("_embedded").path("dav:item").get(0);
        String href = item.path("_links").path("self").path("href").asText();
        var configuration = sabreDavExtension.dockerSabreDavSetup().davConfiguration();
        String authorization = HttpUtils.createBasicAuthenticationToken(
            configuration.adminCredential().getUserName() + "&" + requester.username().asString(),
            configuration.adminCredential().getPassword());
        HttpRequest detailRequest = HttpRequest.newBuilder(configuration.baseUrl().resolve(href))
            .header("Authorization", authorization)
            .GET()
            .build();
        HttpResponse<String> detailResponse = HttpClient.newHttpClient().send(detailRequest, HttpResponse.BodyHandlers.ofString());

        // Then the href resolves to the same contact instead of returning 404
        assertThat(href).isEqualTo("/addressbooks/" + requester.id().value() + "/contacts/detail.vcf");
        assertThat(item.path("etag").asText()).isNotBlank();
        assertThat(item.path("data").get(0).asText()).isEqualTo("vcard");
        assertThat(item.path("data").toString()).contains("grepme Detail Contact");
        assertThat(detailResponse.statusCode()).isEqualTo(SC_OK);
        assertThat(detailResponse.body()).contains("FN:grepme Detail Contact");
    }

    @Test
    void shouldRejectNullAddressBook() {
        // Given a request containing a null address book
        // When searching contacts, then the invalid request is rejected
        given()
            .body("""
                {
                  "addressBooks": [null]
                }
                """)
            .post(ROUTE)
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidAddressBookRequests")
    void shouldRejectMissingOrInvalidAddressBookIdentifiers(String scenario, String requestTemplate) {
        // Given an address book reference with a missing, blank, or unsafe URI path segment
        // When searching with the invalid reference
        String response = given()
            .body(requestTemplate.replace("{userId}", requester.id().value()))
            .post(ROUTE)
            .then()
            .statusCode(SC_BAD_REQUEST)
            .extract()
            .body()
            .asString();

        // Then the shared bad-request handler returns the validation error as JSON
        assertThatJson(response).isEqualTo("""
            {
              "error": {
                "code": 400,
                "type": "BadRequest",
                "message": "Bad request",
                "details": "Address book IDs must be non-empty URI path segments"
              }
            }
            """);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequestBodies")
    void shouldRejectInvalidRequestBody(String scenario, String requestBody, String expectedDetails) {
        // Given an empty, malformed, or structurally invalid JSON body
        // When submitting the invalid body
        String response = given()
            .body(requestBody)
            .post(ROUTE)
            .then()
            .statusCode(SC_BAD_REQUEST)
            .extract()
            .body()
            .asString();

        // Then the shared bad-request handler reports the parsing failure
        assertThatJson(response).isEqualTo("""
            {
              "error": {
                "code": 400,
                "type": "BadRequest",
                "message": "Bad request",
                "details": "{details}"
              }
            }
            """.replace("{details}", expectedDetails));
    }

    @Test
    void shouldRejectInvalidLimitAndOffset() {
        // Given zero, negative, non-numeric, and overflowing pagination values
        // When searching contacts, then each invalid request is rejected with 400
        for (String invalidLimit : List.of("0", "-1", "not-a-number")) {
            given()
                .queryParam("limit", invalidLimit)
                .body("{}")
                .post(ROUTE)
                .then()
                .statusCode(SC_BAD_REQUEST);
        }
        for (String invalidOffset : List.of("-1", "not-a-number")) {
            given()
                .queryParam("offset", invalidOffset)
                .body("{}")
                .post(ROUTE)
                .then()
                .statusCode(SC_BAD_REQUEST);
        }
        given()
            .queryParam("limit", 1)
            .queryParam("offset", Integer.MAX_VALUE)
            .body("{}")
            .post(ROUTE)
            .then()
            .statusCode(SC_BAD_REQUEST);
    }

    private static Stream<Arguments> queryFieldsSearchingAllContacts() {
        return Stream.of(
            Arguments.of("missing query", ""),
            Arguments.of("null query", "\"query\": null,"),
            Arguments.of("empty query", "\"query\": \"\","));
    }

    private static Stream<Arguments> invalidAddressBookRequests() {
        return Stream.of(
            Arguments.of("missing userId", """
                {
                  "addressBooks": [
                    { "addressBookId": "contacts" }
                  ]
                }
                """),
            Arguments.of("missing addressBookId", """
                {
                  "addressBooks": [
                    { "userId": "{userId}" }
                  ]
                }
                """),
            Arguments.of("blank addressBookId", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": " " }
                  ]
                }
                """),
            Arguments.of("path separator", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts/private" }
                  ]
                }
                """),
            Arguments.of("query delimiter", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts?limit=100" }
                  ]
                }
                """),
            Arguments.of("fragment delimiter", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "contacts#private" }
                  ]
                }
                """),
            Arguments.of("current directory segment", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": "." }
                  ]
                }
                """),
            Arguments.of("parent directory segment", """
                {
                  "addressBooks": [
                    { "userId": "{userId}", "addressBookId": ".." }
                  ]
                }
                """));
    }

    private static Stream<Arguments> invalidRequestBodies() {
        return Stream.of(
            Arguments.of("empty body", "", "Request body must be an object"),
            Arguments.of("malformed JSON", "{", "Invalid request body"),
            Arguments.of("array root", "[]", "Invalid request body"),
            Arguments.of("addressBooks is not an array", """
                {
                  "addressBooks": {}
                }
                """, "Invalid request body"),
            Arguments.of("trailing JSON token", """
                {
                  "addressBooks": []
                }
                {}
                """, "Invalid request body"));
    }

    private void createContact(OpenPaaSUser owner, String bookId, String uid, String name) {
        String vcard = """
            BEGIN:VCARD
            VERSION:3.0
            UID:{uid}
            FN:{name}
            END:VCARD
            """.replace("{uid}", uid).replace("{name}", name);
        cardDavClient.upsertContact(owner.username(), new AddressBookURL(owner.id(), bookId), uid,
            vcard.getBytes(StandardCharsets.UTF_8)).block();
    }
}
