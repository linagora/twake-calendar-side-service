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

import java.util.Optional;

import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.backends.opensearch.IndexCreationFactory;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.opensearch.client.RestClient;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.opensearch.client.opensearch.OpenSearchAsyncClient;
import org.opensearch.client.transport.rest_client.RestClientTransport;

import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.CalendarSearchServiceContract;

public class OpensearchCalendarSearchServiceTest implements CalendarSearchServiceContract {
    private static final CalendarEventOpensearchConfiguration CALENDAR_EVENT_OPENSEARCH_CONFIGURATION = CalendarEventOpensearchConfiguration.DEFAULT;

    @RegisterExtension
    public final DockerOpenSearchExtension openSearch = new DockerOpenSearchExtension();

    private OpensearchCalendarSearchService calendarSearchService;

    @BeforeEach
    void setup() {
        CalendarEventIndexMappingFactory calendarEventIndexMappingFactory = new CalendarEventIndexMappingFactory();
        ReactorOpenSearchClient client = openSearch.getDockerOpenSearch().clientProvider().get();

        new IndexCreationFactory(OpenSearchConfiguration.DEFAULT_CONFIGURATION)
            .useIndex(CALENDAR_EVENT_OPENSEARCH_CONFIGURATION.indexName())
            .addAlias(CALENDAR_EVENT_OPENSEARCH_CONFIGURATION.readAliasName())
            .addAlias(CALENDAR_EVENT_OPENSEARCH_CONFIGURATION.writeAliasName())
            .createIndexAndAliases(client, Optional.of(calendarEventIndexMappingFactory.indexSettings(CALENDAR_EVENT_OPENSEARCH_CONFIGURATION)),
                Optional.of(calendarEventIndexMappingFactory.createTypeMapping()));

        RestClient lowLevelClient = client.getLowLevelClient();
        RestClientTransport transport = new RestClientTransport(lowLevelClient, new JacksonJsonpMapper());
        OpenSearchAsyncClient openSearchAsyncClient = new OpenSearchAsyncClient(transport);

        calendarSearchService = new OpensearchCalendarSearchService(client, openSearchAsyncClient, CALENDAR_EVENT_OPENSEARCH_CONFIGURATION);
    }

    @Override
    public CalendarSearchService testee() {
        return calendarSearchService;
    }
}
