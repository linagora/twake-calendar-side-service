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

import org.apache.commons.configuration2.Configuration;
import org.apache.james.backends.opensearch.IndexName;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.opensearch.ReadAliasName;
import org.apache.james.backends.opensearch.WriteAliasName;

import com.google.common.base.Preconditions;

public record CalendarEventOpensearchConfiguration(IndexName indexName,
                                                   ReadAliasName readAliasName,
                                                   WriteAliasName writeAliasName,
                                                   int minNgram,
                                                   int maxNgramDiff,
                                                   int nbShards,
                                                   int nbReplicas,
                                                   boolean searchSummaryPrefix,
                                                   boolean fuzzySearch,
                                                   boolean useQueryStringQuery) {

    public CalendarEventOpensearchConfiguration {
        Preconditions.checkArgument(minNgram > 0, "minNgram must be greater than 0");
        Preconditions.checkArgument(maxNgramDiff > 0, "maxNgramDiff must be greater than 0");
        Preconditions.checkArgument(nbShards > 0, "nbShards must be greater than 0");
        Preconditions.checkArgument(nbReplicas >= 0, "nbReplicas must be greater than or equal to 0");
    }

    public static CalendarEventOpensearchConfiguration fromProperties(Configuration configuration) {
        IndexName indexName = Optional.ofNullable(configuration.getString(PROPERTY_INDEX_CALENDAR_EVENTS_NAME))
            .map(IndexName::new)
            .orElse(DEFAULT_INDEX_NAME);
        ReadAliasName readAliasName = Optional.ofNullable(configuration.getString(PROPERTY_ALIAS_READ_CALENDAR_EVENTS_NAME))
            .map(ReadAliasName::new)
            .orElse(DEFAULT_READ_ALIAS_NAME);

        WriteAliasName writeAliasName = Optional.ofNullable(configuration.getString(PROPERTY_ALIAS_WRITE_CALENDAR_EVENTS_NAME))
            .map(WriteAliasName::new)
            .orElse(DEFAULT_WRITE_ALIAS_NAME);

        int minNgram = configuration.getInteger(PROPERTY_INDEX_CALENDAR_EVENTS_MIN_NGRAM, DEFAULT_MIN_NGRAM);
        int maxNgramDiff = configuration.getInteger(PROPERTY_INDEX_CALENDAR_EVENTS_MAX_NGRAM_DIFF, DEFAULT_MAX_NGRAM_DIFF);

        int nbShards = configuration.getInteger(OpenSearchConfiguration.OPENSEARCH_NB_SHARDS, OpenSearchConfiguration.DEFAULT_NB_SHARDS);
        int nbReplicas = configuration.getInteger(OpenSearchConfiguration.OPENSEARCH_NB_REPLICA, OpenSearchConfiguration.DEFAULT_NB_REPLICA);

        boolean searchSummaryPrefix = configuration.getBoolean(OPENSEARCH_INDEX_CALENDAR_EVENTS_SUMMARY_SEARCH_PREFIX, DEFAULT_SUMMARY_SEARCH_PREFIX);
        boolean fuzzySearch = configuration.getBoolean(OPENSEARCH_INDEX_CALENDAR_EVENTS_FUZZY_SEARCH, DEFAULT_FUZZY_SEARCH);
        boolean queryStringQuery = configuration.getBoolean(OPENSEARCH_INDEX_CALENDAR_EVENTS_QUERY_STRING_QUERY, DEFAULT_QUERY_STRING_QUERY);

        return new CalendarEventOpensearchConfiguration(indexName, readAliasName,
            writeAliasName, minNgram, maxNgramDiff,
            nbShards, nbReplicas, searchSummaryPrefix,
            fuzzySearch, queryStringQuery);
    }

    public static final IndexName DEFAULT_INDEX_NAME = new IndexName("calendar-events");
    public static final ReadAliasName DEFAULT_READ_ALIAS_NAME = new ReadAliasName("calendar-events-read");
    public static final WriteAliasName DEFAULT_WRITE_ALIAS_NAME = new WriteAliasName("calendar-events-write");
    public static final int DEFAULT_MIN_NGRAM = 3;
    public static final int DEFAULT_MAX_NGRAM_DIFF = 4;
    public static final boolean DEFAULT_SUMMARY_SEARCH_PREFIX = true;
    public static final boolean DEFAULT_FUZZY_SEARCH = false;
    public static final boolean DEFAULT_QUERY_STRING_QUERY = true;

    private static final String PROPERTY_INDEX_CALENDAR_EVENTS_NAME = "opensearch.index.calendar.events.name";
    private static final String PROPERTY_ALIAS_READ_CALENDAR_EVENTS_NAME = "opensearch.alias.read.calendar.events.name";
    private static final String PROPERTY_ALIAS_WRITE_CALENDAR_EVENTS_NAME = "opensearch.alias.write.calendar.events.name";
    private static final String PROPERTY_INDEX_CALENDAR_EVENTS_MAX_NGRAM_DIFF = "opensearch.index.calendar.events.max.ngram.diff";
    private static final String PROPERTY_INDEX_CALENDAR_EVENTS_MIN_NGRAM = "opensearch.index.calendar.events.min.ngram";
    private static final String OPENSEARCH_INDEX_CALENDAR_EVENTS_SUMMARY_SEARCH_PREFIX = "opensearch.index.calendar.events.summary.searchPrefix";
    private static final String OPENSEARCH_INDEX_CALENDAR_EVENTS_FUZZY_SEARCH = "opensearch.index.calendar.events.fuzzySearch";
    private static final String OPENSEARCH_INDEX_CALENDAR_EVENTS_QUERY_STRING_QUERY = "opensearch.index.calendar.events.queryStringQuery";

    public static CalendarEventOpensearchConfiguration DEFAULT =
        new CalendarEventOpensearchConfiguration(
            DEFAULT_INDEX_NAME,
            DEFAULT_READ_ALIAS_NAME,
            DEFAULT_WRITE_ALIAS_NAME,
            DEFAULT_MIN_NGRAM,
            DEFAULT_MAX_NGRAM_DIFF,
            OpenSearchConfiguration.DEFAULT_NB_SHARDS,
            OpenSearchConfiguration.DEFAULT_NB_REPLICA,
            DEFAULT_SUMMARY_SEARCH_PREFIX,
            DEFAULT_FUZZY_SEARCH,
            DEFAULT_QUERY_STRING_QUERY);
}
