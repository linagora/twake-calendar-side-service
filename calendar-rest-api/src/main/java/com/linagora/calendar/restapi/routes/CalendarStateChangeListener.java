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

package com.linagora.calendar.restapi.routes;

import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventListener;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.restapi.routes.WebsocketRoute.CalendarChangeMessage;
import com.linagora.calendar.storage.CalendarChangeEvent;
import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

public record CalendarStateChangeListener(Sinks.Many<CalendarChangeMessage> outbound,
                                          CalDavClient calDavClient,
                                          Username username) implements EventListener.ReactiveEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(CalendarStateChangeListener.class);


    @Override
    public boolean isHandling(Event event) {
        return event instanceof CalendarChangeEvent;
    }

    @Override
    public Publisher<Void> reactiveEvent(Event event) {
        CalendarChangeEvent calendarChangeEvent = (CalendarChangeEvent) event;
        CalendarURL calendarUrl = calendarChangeEvent.calendarURL();
        return retrieveSyncToken(username, calendarUrl)
            .doOnNext(syncToken -> {
                synchronized (outbound) {
                    outbound.emitNext(new CalendarChangeMessage(calendarUrl, syncToken), Sinks.EmitFailureHandler.FAIL_FAST);
                }
            })
            .then();
    }

    private Mono<SyncToken> retrieveSyncToken(Username username, CalendarURL calendarURL) {
        return calDavClient.retrieveSyncToken(username, calendarURL)
            .doOnError(error -> LOGGER.error("Failed to retrieve SyncToken for {}", calendarURL.asUri(), error))
            .onErrorResume(error -> Mono.empty());
    }
}
