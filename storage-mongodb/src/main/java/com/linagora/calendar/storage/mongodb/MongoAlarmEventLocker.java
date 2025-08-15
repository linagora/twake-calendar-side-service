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


import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventLocker;
import com.mongodb.DuplicateKeyException;

import reactor.core.publisher.Mono;

public class MongoAlarmEventLocker implements AlarmEventLocker {

    private final MongoDBAlarmEventLedgerDAO alarmEventLedgeDAO;

    @Inject
    @Singleton
    public MongoAlarmEventLocker(MongoDBAlarmEventLedgerDAO alarmEventLedgeDAO) {
        this.alarmEventLedgeDAO = alarmEventLedgeDAO;
    }

    @Override
    public Mono<Void> lock(AlarmEvent alarmEvent) {
        return alarmEventLedgeDAO.insert(alarmEvent)
            .onErrorMap(DuplicateKeyException.class, error -> new LockAlreadyExistsException());
    }
}
