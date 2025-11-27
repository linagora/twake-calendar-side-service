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

package com.linagora.calendar.storage.redis;

import org.apache.james.events.CalendarEventSerializer;
import org.apache.james.events.CalendarRedisEventBus;
import org.apache.james.events.EventBus;
import org.apache.james.events.EventBusId;
import org.apache.james.events.EventSerializer;
import org.apache.james.events.RetryBackoffConfiguration;

import com.google.inject.AbstractModule;

public class RedisEventBusModule extends AbstractModule {
    @Override
    protected void configure() {
        // TODO complete bindings
        bind(RetryBackoffConfiguration.class).toInstance(RetryBackoffConfiguration.DEFAULT);
        bind(EventSerializer.class).to(CalendarEventSerializer.class);
        bind(EventBusId.class).toInstance(EventBusId.random());
        bind(EventBus.class).to(CalendarRedisEventBus.class);
    }
}
