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

import java.io.FileNotFoundException;

import jakarta.inject.Singleton;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.opensearch.OpenSearchConfiguration;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;

public class OpensearchCalendarSearchModule extends AbstractModule {

    public static final Module OPEN_SEARCH_CONFIGURATION_MODULE = new AbstractModule() {

        @Provides
        @Singleton
        public OpenSearchConfiguration provideOpenSearchConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
            try {
                Configuration configuration = propertiesProvider.getConfiguration("opensearch");
                return OpenSearchConfiguration.fromProperties(configuration);
            } catch (FileNotFoundException e) {
                LOGGER.warn("Could not find opensearch configuration file. Using default opensearch configuration");
                return OpenSearchConfiguration.DEFAULT_CONFIGURATION;
            }
        }
    };

    private static final Logger LOGGER = LoggerFactory.getLogger(OpensearchCalendarSearchModule.class);

    @Override
    protected void configure() {
        bind(OpensearchCalendarSearchService.class).in(Scopes.SINGLETON);
        bind(CalendarSearchService.class).to(OpensearchCalendarSearchService.class);
    }

    @ProvidesIntoSet
    InitializationOperation createIndex(CalendarEventIndexMappingFactory.IndexCreator instance) {
        return InitilizationOperationBuilder
            .forClass(CalendarEventIndexMappingFactory.IndexCreator.class)
            .init(instance::createIndexMapping);
    }

    @Provides
    @Singleton
    private CalendarEventOpensearchConfiguration provideCalendarEventOpensearchConfiguration(PropertiesProvider propertiesProvider) {
        try {
            Configuration configuration = propertiesProvider.getConfiguration("opensearch");
            return CalendarEventOpensearchConfiguration.fromProperties(configuration);
        } catch (FileNotFoundException | ConfigurationException e) {
            LOGGER.warn("Could not find opensearch configuration file. Using default calendar event search configuration");
            return CalendarEventOpensearchConfiguration.DEFAULT;
        }
    }
}
