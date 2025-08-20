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

package com.linagora.calendar.scheduling;

import java.io.FileNotFoundException;

import jakarta.inject.Named;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.linagora.calendar.storage.AlarmEventLeaseProvider;

public class AlarmEventSchedulerModule extends AbstractModule {

    @ProvidesIntoSet
    InitializationOperation start(AlarmEventScheduler alarmEventScheduler) {
        return InitilizationOperationBuilder
            .forClass(AlarmEventScheduler.class)
            .init(alarmEventScheduler::start);
    }

    @Provides
    @Singleton
    AlarmEventSchedulerConfiguration provideAlarmEventSchedulerConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return AlarmEventSchedulerConfiguration.from(propertiesProvider.getConfiguration("configuration"));
    }

    @Provides
    @Named("scheduler")
    AlarmEventLeaseProvider provideAlarmEventLeaseProvider(AlarmEventSchedulerConfiguration configuration,
                                                           AlarmEventLeaseProvider candidate) {
        if (AlarmEventSchedulerConfiguration.Mode.SINGLE.equals(configuration.mode())) {
            return AlarmEventLeaseProvider.NOOP;
        } else {
            return candidate;
        }
    }
}
