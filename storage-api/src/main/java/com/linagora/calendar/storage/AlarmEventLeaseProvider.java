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

package com.linagora.calendar.storage;

import java.time.Duration;

import reactor.core.publisher.Mono;

public interface AlarmEventLeaseProvider {

    Mono<Void> acquire(AlarmEvent alarmEvent, Duration ttl);

    Mono<Void> release(AlarmEvent alarmEvent);

    class LockAlreadyExistsException extends RuntimeException {

    }

    AlarmEventLeaseProvider NOOP = new NoOpAlarmEventLeaseProvider();

    class NoOpAlarmEventLeaseProvider implements AlarmEventLeaseProvider {

        @Override
        public Mono<Void> acquire(AlarmEvent alarmEvent, Duration ttl) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> release(AlarmEvent alarmEvent) {
            return Mono.empty();
        }
    }
}
