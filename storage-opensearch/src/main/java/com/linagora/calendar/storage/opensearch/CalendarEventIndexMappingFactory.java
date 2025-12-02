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

import static org.apache.james.backends.opensearch.IndexCreationFactory.CASE_INSENSITIVE;
import static org.apache.james.backends.opensearch.IndexCreationFactory.RAW;

import java.util.Optional;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.james.backends.opensearch.IndexCreationFactory;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.apache.james.lifecycle.api.Startable;
import org.opensearch.client.opensearch._types.analysis.Analyzer;
import org.opensearch.client.opensearch._types.analysis.AsciiFoldingTokenFilter;
import org.opensearch.client.opensearch._types.analysis.CustomAnalyzer;
import org.opensearch.client.opensearch._types.analysis.CustomNormalizer;
import org.opensearch.client.opensearch._types.analysis.EdgeNGramTokenFilter;
import org.opensearch.client.opensearch._types.analysis.Normalizer;
import org.opensearch.client.opensearch._types.analysis.TokenFilter;
import org.opensearch.client.opensearch._types.analysis.TokenFilterDefinition;
import org.opensearch.client.opensearch._types.mapping.BooleanProperty;
import org.opensearch.client.opensearch._types.mapping.DateProperty;
import org.opensearch.client.opensearch._types.mapping.KeywordProperty;
import org.opensearch.client.opensearch._types.mapping.ObjectProperty;
import org.opensearch.client.opensearch._types.mapping.Property;
import org.opensearch.client.opensearch._types.mapping.TextProperty;
import org.opensearch.client.opensearch._types.mapping.TypeMapping;
import org.opensearch.client.opensearch.indices.IndexSettings;
import org.opensearch.client.opensearch.indices.IndexSettingsAnalysis;

import com.google.common.collect.ImmutableMap;

public class CalendarEventIndexMappingFactory {

    interface CalendarFields {

        String ACCOUNT_ID = "accountId";
        String EVENT_UID = "eventUid";
        String OPENPAAS_USER_ID = "userId";
        String CALENDAR_URL = "calendarURL";

        String SUMMARY = "summary";
        String DESCRIPTION = "description";
        String LOCATION = "location";

        String CN = "cn";
        String EMAIL = "email";
        String ORGANIZER = "organizer";
        String ATTENDEES = "attendees";
        String RESOURCES = "resources";

        String START = "start";
        String END = "end";
        String DTSTAMP = "dtstamp";
        String ALL_DAY = "allDay";
        String IS_RECURRENT_MASTER = "isRecurrentMaster";
        String CLAZZ = "clazz";
        String VIDEOCONFERENCE_URL = "videoconferenceUrl";
        String SEQUENCE = "sequence";
    }

    interface CalendarAnalyzers {
        String STANDARD = "standard";
        String PREFIX_KEY = "calendar_event_";
        String CN_NAME_ANALYZER = PREFIX_KEY + "cn_name_analyzer";
        String KEEP_MAIL_AND_URL_ANALYZER = PREFIX_KEY + "keep_mail_and_url_analyzer";
        String LOCATION_ANALYZER = PREFIX_KEY + "location_analyzer";

        String EDGE_NGRAM_FILTER = "edge_ngram_filter";
        String PRESERVED_ASCII_FOLDING_FILTER = "preserved_ascii_folding_filter";

        ImmutableMap<String, Analyzer> MAPPING_ANALYZERS = ImmutableMap.<String, Analyzer>builder()
            .put(CN_NAME_ANALYZER, new Analyzer(new CustomAnalyzer.Builder()
                .tokenizer(STANDARD)
                .filter(EDGE_NGRAM_FILTER, "lowercase", PRESERVED_ASCII_FOLDING_FILTER)
                .build()))
            .put(KEEP_MAIL_AND_URL_ANALYZER, new Analyzer(new CustomAnalyzer.Builder()
                .tokenizer("uax_url_email")
                .filter("lowercase", "stop")
                .build()))
            .put(LOCATION_ANALYZER, new Analyzer(new CustomAnalyzer.Builder()
                .tokenizer(STANDARD)
                .filter("lowercase")
                .build()))
            .build();

        Function<CalendarEventOpensearchConfiguration, ImmutableMap<String, TokenFilter>> MAPPING_FILTERS_FUNCTION = config -> ImmutableMap.<String, TokenFilter>builder()
            .put(EDGE_NGRAM_FILTER, new TokenFilter.Builder()
                .definition(new TokenFilterDefinition(new EdgeNGramTokenFilter.Builder()
                    .minGram(config.minNgram())
                    .maxGram(config.minNgram() + config.maxNgramDiff())
                    .build()))
                .build())
            .put(PRESERVED_ASCII_FOLDING_FILTER, new TokenFilter.Builder()
                .definition(new TokenFilterDefinition(new AsciiFoldingTokenFilter.Builder()
                    .preserveOriginal(true)
                    .build()))
                .build())
            .build();
    }

    public IndexSettings indexSettings(CalendarEventOpensearchConfiguration configuration) {
        return new IndexSettings.Builder()
            .numberOfShards(Integer.toString(configuration.nbShards()))
            .numberOfReplicas(Integer.toString(configuration.nbReplicas()))
            .index(new IndexSettings.Builder()
                .maxNgramDiff(configuration.maxNgramDiff())
                .build())
            .analysis(new IndexSettingsAnalysis.Builder()
                .normalizer(CASE_INSENSITIVE, new Normalizer.Builder()
                    .custom(new CustomNormalizer.Builder()
                        .filter("lowercase", "asciifolding")
                        .build())
                    .build())
                .analyzer(CalendarAnalyzers.MAPPING_ANALYZERS)
                .filter(CalendarAnalyzers.MAPPING_FILTERS_FUNCTION.apply(configuration))
                .build())
            .build();
    }

    public TypeMapping createTypeMapping() {
        Property nonIndexedDateProperty = new Property(new DateProperty.Builder().index(false).build());
        Property nonIndexedKeywordProperty = new Property(new KeywordProperty.Builder().index(false).build());
        Property nonIndexedBooleanProperty = new Property(new BooleanProperty.Builder().index(false).build());
        Property indexedKeywordProperty = new Property(new KeywordProperty.Builder().index(true).build());

        Property emailTextProperty = new TextProperty.Builder()
            .analyzer(CalendarAnalyzers.STANDARD)
            .fields(RAW, new Property.Builder()
                .keyword(new KeywordProperty.Builder().normalizer(CASE_INSENSITIVE).build())
                .build())
            .build()._toProperty();

        Property cnNameTextProperty = new TextProperty.Builder()
            .analyzer(CalendarAnalyzers.CN_NAME_ANALYZER)
            .build()._toProperty();

        Property emailCNObjectProperty = new ObjectProperty.Builder()
            .properties(ImmutableMap.of(
                CalendarFields.EMAIL, emailTextProperty,
                CalendarFields.CN, cnNameTextProperty))
            .build()._toProperty();

        Property sortableDateProperty = new Property(new DateProperty.Builder().index(true).build());

        return new TypeMapping.Builder()
            .properties(new ImmutableMap.Builder<String, Property>()
                .put(CalendarFields.ACCOUNT_ID, indexedKeywordProperty)
                .put(CalendarFields.EVENT_UID, indexedKeywordProperty)
                .put(CalendarFields.CALENDAR_URL, indexedKeywordProperty)
                .put(CalendarFields.SUMMARY, new Property(new TextProperty.Builder().analyzer(CalendarAnalyzers.KEEP_MAIL_AND_URL_ANALYZER).build()))
                .put(CalendarFields.LOCATION,  new Property(new TextProperty.Builder().analyzer(CalendarAnalyzers.LOCATION_ANALYZER).build()))
                .put(CalendarFields.DESCRIPTION,  new Property(new TextProperty.Builder().analyzer(CalendarAnalyzers.STANDARD).build()))
                .put(CalendarFields.ORGANIZER, emailCNObjectProperty)
                .put(CalendarFields.ATTENDEES, emailCNObjectProperty)
                .put(CalendarFields.RESOURCES, emailCNObjectProperty)
                // non indexed properties
                .put(CalendarFields.START, sortableDateProperty)
                .put(CalendarFields.END, nonIndexedDateProperty)
                .put(CalendarFields.DTSTAMP, nonIndexedDateProperty)
                .put(CalendarFields.OPENPAAS_USER_ID, nonIndexedKeywordProperty)
                .put(CalendarFields.CLAZZ, nonIndexedKeywordProperty)
                .put(CalendarFields.ALL_DAY, nonIndexedBooleanProperty)
                .put(CalendarFields.IS_RECURRENT_MASTER, nonIndexedBooleanProperty)
                .put(CalendarFields.VIDEOCONFERENCE_URL, nonIndexedKeywordProperty)
                .build())
            .build();
    }

    public static class IndexCreator implements Startable {
        private final CalendarEventOpensearchConfiguration configuration;
        private final OpenSearchConfiguration openSearchConfiguration;
        private final ReactorOpenSearchClient client;

        @Inject
        public IndexCreator(CalendarEventOpensearchConfiguration configuration,
                            OpenSearchConfiguration openSearchConfiguration,
                            ReactorOpenSearchClient client) {
            this.configuration = configuration;
            this.openSearchConfiguration = openSearchConfiguration;
            this.client = client;
        }

        public void createIndexMapping() {
            CalendarEventIndexMappingFactory mappingFactory = new CalendarEventIndexMappingFactory();
            new IndexCreationFactory(openSearchConfiguration)
                .useIndex(configuration.indexName())
                .addAlias(configuration.writeAliasName())
                .addAlias(configuration.readAliasName())
                .createIndexAndAliases(client, Optional.of(mappingFactory.indexSettings(configuration)),
                    Optional.of(mappingFactory.createTypeMapping()));
        }
    }
}