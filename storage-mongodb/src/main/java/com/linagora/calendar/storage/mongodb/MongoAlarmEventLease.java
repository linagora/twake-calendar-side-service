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

package com.linagora.calendar.storage.mongodb;

import static com.linagora.calendar.storage.mongodb.MongoDBAlarmEventLedgerDAO.UNIQUE_INDEX_NAME;

import java.time.Duration;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;

import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventLease;

import reactor.core.publisher.Mono;

public class MongoAlarmEventLease implements AlarmEventLease {

    private final MongoDBAlarmEventLedgerDAO alarmEventLedgeDAO;

    @Inject
    @Singleton
    public MongoAlarmEventLease(MongoDBAlarmEventLedgerDAO alarmEventLedgeDAO) {
        this.alarmEventLedgeDAO = alarmEventLedgeDAO;
    }

    @Override
    public Mono<Void> acquire(AlarmEvent alarmEvent, Duration ttl) {
        return alarmEventLedgeDAO.insert(alarmEvent, ttl)
            .onErrorResume(error -> {
                if (Strings.CI.contains(error.getMessage(), "duplicate key error collection")
                    && Strings.CI.contains(error.getMessage(), UNIQUE_INDEX_NAME)) {
                    return Mono.error(new LockAlreadyExistsException());
                }
                return Mono.error(error);
            });
    }
}
