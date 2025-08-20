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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventLease;
import com.linagora.calendar.storage.eventsearch.EventUid;

import reactor.core.publisher.Mono;

public class MongoAlarmEventLeaseTest {

    static final Duration ttl = Duration.ofSeconds(100);

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of("twake_calendar_alarm_events_ledge"));

    private MongoAlarmEventLease testee;

    @BeforeEach
    void setUp() {
        MongoDBAlarmEventLedgerDAO mongoDBAlarmEventLedgerDAO = new MongoDBAlarmEventLedgerDAO(mongo.getDb(), Clock.systemDefaultZone());
        testee = new MongoAlarmEventLease(mongoDBAlarmEventLedgerDAO);
    }

    private AlarmEvent sampleEvent() {
        Instant now = Instant.now();
        EventUid eventUid = new EventUid("event-uid-" + UUID.randomUUID());
        return new AlarmEvent(
            eventUid,
            now.minusSeconds(10),
            now.plusSeconds(3600),
            false,
            Optional.empty(),
            Throwing.supplier(() -> new MailAddress("attendee@abc.com")).get(),
            "");
    }

    private long countDocuments() {
        return Mono.from(mongo.getDb()
            .getCollection(MongoDBAlarmEventLedgerDAO.COLLECTION)
            .countDocuments()).block();
    }

    @Test
    void shouldNotThrowForFirstTime() {
        AlarmEvent event = sampleEvent();
        assertThatCode(() -> testee.acquire(event, ttl).block())
            .doesNotThrowAnyException();
    }

    @Test
    void shouldThrowWhenAlreadyExists() {
        AlarmEvent event = sampleEvent();

        testee.acquire(event, ttl).block();

        assertThatThrownBy(() -> testee.acquire(event, ttl).block())
            .isInstanceOf(AlarmEventLease.LockAlreadyExistsException.class);
    }

    @Test
    void shouldSaveRecordWithTTL() {
        AlarmEvent event = sampleEvent();

        testee.acquire(event, Duration.ofSeconds(1)).block();
        assertThat(countDocuments()).isEqualTo(1);

        Awaitility.with()
            .pollInterval(Duration.ofSeconds(1))
            .await()
            .atMost(Duration.ofMinutes(2))
            .untilAsserted(() -> assertThat(countDocuments()).isEqualTo(0));
    }

}
