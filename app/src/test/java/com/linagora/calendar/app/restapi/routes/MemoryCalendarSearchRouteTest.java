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

import static com.linagora.calendar.app.AppTestHelper.BY_PASS_MODULE;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.dav.CalendarSearchSourceResolver;
import com.linagora.calendar.dav.DavModuleTestHelper;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;

import reactor.core.publisher.Mono;

public class MemoryCalendarSearchRouteTest implements CalendarSearchRouteContract {
    @RegisterExtension
    @Order(1)
    private static final RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    static TwakeCalendarExtension extension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        BY_PASS_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        new AbstractModule() {
            @Provides
            @Singleton
            CalendarSearchSourceResolver calendarSearchSourceResolver() {
                // Memory route tests do not start SabreDAV; keep resolution local to the requester's default calendar.
                return new CalendarSearchSourceResolver(null) {
                    @Override
                    public Mono<Map<CalendarURL, CalendarURL>> resolve(OpenPaaSUser requester,
                                                                        List<CalendarURL> requestedCalendars) {
                        CalendarURL defaultCalendarURL = CalendarURL.from(requester.id());
                        Map<CalendarURL, CalendarURL> resolvedCalendars = new LinkedHashMap<>();

                        requestedCalendars.stream()
                            .filter(defaultCalendarURL::equals)
                            .forEach(calendarURL -> resolvedCalendars.put(calendarURL, calendarURL));

                        return Mono.just(resolvedCalendars);
                    }
                };
            }
        });
}
