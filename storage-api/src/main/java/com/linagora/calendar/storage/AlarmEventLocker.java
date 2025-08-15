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

import reactor.core.publisher.Mono;

public interface AlarmEventLocker {

    Mono<Void> lock(AlarmEvent alarmEvent);

    class LockAlreadyExistsException extends RuntimeException {

    }

    AlarmEventLocker NOOP = new NoOpAlarmEventLocker();

    class NoOpAlarmEventLocker implements AlarmEventLocker {

        public NoOpAlarmEventLocker() {
        }

        @Override
        public Mono<Void> lock(AlarmEvent alarmEvent) {
            return Mono.empty();
        }
    }
}
