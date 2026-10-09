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

package com.linagora.calendar.amqp;

import static com.linagora.calendar.dav.SabreDavProvisioningService.DOMAIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.james.core.Username;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

class ItipLocalDeliveryProvisioningTest {
    private static final ConditionFactory AWAIT_AT_MOST = Awaitility.with()
        .pollInterval(Duration.ofMillis(500))
        .pollDelay(Duration.ofMillis(500))
        .await()
        .atMost(20, TimeUnit.SECONDS);
    private static final ConditionFactory NEGATIVE_AWAIT = Awaitility.with()
        .pollInterval(Duration.ofMillis(200))
        .pollDelay(Duration.ZERO)
        .await()
        .during(Duration.ofSeconds(3));

    @RegisterExtension
    static SabreDavWithAsyncSchedulingExtension sabreDavExtension = new SabreDavWithAsyncSchedulingExtension();

    private OpenPaaSUserDAO userDAO;
    private OpenPaaSUser organizer;
    private Username attendee;

    @BeforeEach
    void setUp() {
        MongoDatabase mongoDB = sabreDavExtension.dockerSabreDavSetup().getMongoDB();
        userDAO = new MongoDBOpenPaaSUserDAO(mongoDB, new MongoDBOpenPaaSDomainDAO(mongoDB));
        organizer = sabreDavExtension.newTestUser();
        attendee = Username.fromLocalPartWithDomain("ldap_" + UUID.randomUUID(), DOMAIN);
    }

    @Test
    void invitationShouldBeDeliveredToUserOnlyKnownByUsersRepository() {
        when(sabreDavExtension.usersRepository().containsReactive(attendee)).thenReturn(Mono.just(true));
        String eventUid = UUID.randomUUID().toString();

        sabreDavExtension.davTestHelper().upsertCalendar(organizer, invitation(eventUid), eventUid);

        AWAIT_AT_MOST.untilAsserted(() -> {
            OpenPaaSUser provisionedAttendee = userDAO.retrieve(attendee).block();
            assertThat(provisionedAttendee).isNotNull();
            assertThat(sabreDavExtension.davTestHelper().findFirstEventId(provisionedAttendee)).isPresent();
        });
    }

    @Test
    void invitationShouldNotProvisionUserUnknownByUsersRepository() {
        String eventUid = UUID.randomUUID().toString();

        sabreDavExtension.davTestHelper().upsertCalendar(organizer, invitation(eventUid), eventUid);

        NEGATIVE_AWAIT.untilAsserted(() -> assertThat(userDAO.retrieve(attendee).blockOptional()).isEmpty());
    }

    private String invitation(String eventUid) {
        return """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Sabre//Sabre VObject 4.1.3//EN
            CALSCALE:GREGORIAN
            BEGIN:VEVENT
            UID:{eventUid}
            DTSTAMP:20300101T000000Z
            DTSTART:20300101T100000Z
            DTEND:20300101T110000Z
            SUMMARY:Provisioning test
            ORGANIZER:mailto:{organizer}
            ATTENDEE;PARTSTAT=ACCEPTED:mailto:{organizer}
            ATTENDEE;PARTSTAT=NEEDS-ACTION;RSVP=TRUE:mailto:{attendee}
            END:VEVENT
            END:VCALENDAR
            """
            .replace("{eventUid}", eventUid)
            .replace("{organizer}", organizer.username().asString())
            .replace("{attendee}", attendee.asString());
    }
}
