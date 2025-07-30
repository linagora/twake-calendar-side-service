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

import java.time.Instant;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AlarmEventDAO {
    Mono<AlarmEvent> find(String id);

    Mono<Void> create(AlarmEvent alarmEvent);

    Mono<Void> update(AlarmEvent alarmEvent);

    Mono<Void> delete(String id);

    Flux<AlarmEvent> getByTime(Instant time); // get all alarmEvent with alarmTime <= time
}
