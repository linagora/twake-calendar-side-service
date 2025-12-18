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

package com.linagora.calendar.storage.opensearch;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.List;
import java.util.Optional;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.backends.opensearch.IndexCreationFactory;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;

import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.CalendarSearchServiceContract;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;

public class OpensearchCalendarSearchServiceTest implements CalendarSearchServiceContract {
    @RegisterExtension
    public final DockerOpenSearchExtension openSearch = new DockerOpenSearchExtension();

    private CalendarEventOpensearchConfiguration calendarEventOpensearchConfiguration;
    private OpensearchCalendarSearchService calendarSearchService;

    @BeforeEach
    void setup() {
        calendarEventOpensearchConfiguration = Mockito.spy(CalendarEventOpensearchConfiguration.DEFAULT);

        CalendarEventIndexMappingFactory calendarEventIndexMappingFactory = new CalendarEventIndexMappingFactory();
        ReactorOpenSearchClient client = openSearch.getDockerOpenSearch().clientProvider().get();

        new IndexCreationFactory(OpenSearchConfiguration.DEFAULT_CONFIGURATION)
            .useIndex(calendarEventOpensearchConfiguration.indexName())
            .addAlias(calendarEventOpensearchConfiguration.readAliasName())
            .addAlias(calendarEventOpensearchConfiguration.writeAliasName())
            .createIndexAndAliases(client, Optional.of(calendarEventIndexMappingFactory.indexSettings(calendarEventOpensearchConfiguration)),
                Optional.of(calendarEventIndexMappingFactory.createTypeMapping()));

        RestClient lowLevelClient = client.getLowLevelClient();
        RestClientTransport transport = new RestClientTransport(lowLevelClient, new JacksonJsonpMapper());
        OpenSearchAsyncClient openSearchAsyncClient = new OpenSearchAsyncClient(transport);

        calendarSearchService = new OpensearchCalendarSearchService(client, openSearchAsyncClient, calendarEventOpensearchConfiguration);
    }

    @Override
    public CalendarSearchService testee() {
        return calendarSearchService;
    }

    @Test
    void searchShouldOnlyMatchSummaryPrefixThatIsNotOverMaxNgramLength() {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("anticonstitutionnel")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        // Search for a prefix that is not over the max n-gram length
        EventSearchQuery query = simpleQuery("anticon");

        // Search for a prefix that is over the max n-gram length
        EventSearchQuery query2 = simpleQuery("anticons");

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);

            List<EventFields> searchResults2 = testee().search(accountId, query2)
                .collectList().block();

            assertThat(searchResults2).hasSize(0);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sprant", "plenning", "print", "splanning", "sphant"})
    void searchShouldSupportFuzziness(String search) {
        Mockito.when(calendarEventOpensearchConfiguration.fuzzySearch()).thenReturn(true);

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery(search);

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"sphamt", "nning", "strmeeting"})
    void searchShouldNotMatchWhenInputIsTooFuzzy(String search) {
        Mockito.when(calendarEventOpensearchConfiguration.fuzzySearch()).thenReturn(true);

        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        // Verify that document has been indexed
        EventSearchQuery query = simpleQuery("sprint");
        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });

        // Now test with fuzzy input
        EventSearchQuery query2 = simpleQuery(search);

        List<EventFields> searchResults = testee().search(accountId, query2)
            .collectList().block();

        assertThat(searchResults).hasSize(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"sprint planning\"", "sprint*", "sprint -retro"})
    void searchShouldSupportQueryStringQuery(String search) {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("Sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        EventSearchQuery query = simpleQuery(search);

        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"sprint retro\"", "sprant*", "sprint -planning"})
    void searchShouldReturnEmptyResultWhenQueryStringQueryDoesNotMatch(String search) {
        EventFields event = EventFields.builder()
            .uid(generateEventUid())
            .summary("sprint planning meeting")
            .calendarURL(generateCalendarURL())
            .build();

        testee().index(accountId, CalendarEvents.of(event)).block();

        // Verify that document has been indexed
        EventSearchQuery query = simpleQuery("sprint");
        CALMLY_AWAIT.untilAsserted(() -> {
            List<EventFields> searchResults = testee().search(accountId, query)
                .collectList().block();

            assertThat(searchResults).hasSize(1)
                .containsExactly(event);
        });

        // Now test with query string query
        EventSearchQuery query2 = simpleQuery(search);

        List<EventFields> searchResults = testee().search(accountId, query2)
            .collectList().block();

        assertThat(searchResults).hasSize(0);
    }
}
