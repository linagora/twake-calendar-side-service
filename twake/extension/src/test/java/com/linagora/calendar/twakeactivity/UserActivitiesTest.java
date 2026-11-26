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

package com.linagora.calendar.twakeactivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUser;

class UserActivitiesTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Username ALICE = Username.of("alice@linagora.com");
    private static final Username BOB = Username.of("bob@linagora.com");

    private UserActivities testee;
    private OpenPaaSUser alice;
    private OpenPaaSUser bob;

    @BeforeEach
    void setUp() {
        MemoryOpenPaaSUserDAO userDAO = new MemoryOpenPaaSUserDAO();
        alice = userDAO.add(ALICE).block();
        bob = userDAO.add(BOB).block();
        testee = new UserActivities(userDAO, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void eventCreatedShouldPublishTheDocumentedCloudEvent() throws Exception {
        UserActivity activity = handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/%1$s/%1$s/event.ics", "etag": "\\"1\\"", "connectedUser": "principals/users/%1$s", "event": []}
            """.formatted(alice.id().value())).orElseThrow();

        assertThat(activity.type().routingKey()).isEqualTo("calendar.eventCreated");
        JsonNode json = OBJECT_MAPPER.readTree(activity.serialize());
        assertThat(json).isEqualTo(OBJECT_MAPPER.readTree("""
            {
              "specversion": "1.0",
              "id": "%2$s",
              "source": "twake://calendar",
              "type": "com.twake.calendar.eventCreated.v1",
              "time": "2026-10-06T12:00:00Z",
              "subject": "calendars/%1$s/%1$s/event.ics",
              "twakeactor": "alice@linagora.com",
              "twakedomain": "linagora.com",
              "twakeresourceowner": "alice@linagora.com",
              "data": {
                "path": "calendars/%1$s/%1$s/event.ics",
                "calendar": "calendars/%1$s/%1$s"
              }
            }
            """.formatted(alice.id().value(), activity.id())));
    }

    @Test
    void contactCreatedShouldPublishTheDocumentedCloudEvent() throws Exception {
        UserActivity activity = handle(UserActivities.CONTACT_CREATED_EXCHANGE, contact(alice, "principals/users/" + alice.id().value()))
            .orElseThrow();

        assertThat(activity.type().routingKey()).isEqualTo("contacts.contactCreated");
        JsonNode json = OBJECT_MAPPER.readTree(activity.serialize());
        assertThat(json).isEqualTo(OBJECT_MAPPER.readTree("""
            {
              "specversion": "1.0",
              "id": "%2$s",
              "source": "twake://contacts",
              "type": "com.twake.contacts.contactCreated.v1",
              "time": "2026-10-06T12:00:00Z",
              "subject": "addressbooks/%1$s/contacts/card.vcf",
              "twakeactor": "alice@linagora.com",
              "twakedomain": "linagora.com",
              "twakeresourceowner": "alice@linagora.com",
              "data": {
                "path": "addressbooks/%1$s/contacts/card.vcf",
                "addressbook": "addressbooks/%1$s/contacts"
              }
            }
            """.formatted(alice.id().value(), activity.id())));
    }

    @Test
    void actorShouldBeTheConnectedUserRatherThanTheOwner() {
        UserActivity activity = handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/%s/calendar/event.ics", "connectedUser": "principals/users/%s"}
            """.formatted(alice.id().value(), bob.id().value())).orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(BOB);
        assertThat(activity.actors().resourceOwner()).contains(ALICE);
    }

    @Test
    void contactWrittenByADelegateShouldNameTheOwnerAsResourceOwner() {
        UserActivity activity = handle(UserActivities.CONTACT_CREATED_EXCHANGE, contact(alice, "principals/users/" + bob.id().value()))
            .orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(BOB);
        assertThat(activity.actors().resourceOwner()).contains(ALICE);
    }

    @Test
    void domainAddressBookContactShouldHaveNoResourceOwner() throws Exception {
        UserActivity activity = handle(UserActivities.CONTACT_CREATED_EXCHANGE, """
            {"path": "addressbooks/domain/domain-members/card.vcf", "owner": "principals/domains/domain", "etag": "1",
             "carddata": "", "syncToken": 1, "connectedUser": "principals/users/%s"}
            """.formatted(bob.id().value())).orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(BOB);
        assertThat(activity.actors().resourceOwner()).isEmpty();
        assertThat(OBJECT_MAPPER.readTree(activity.serialize()).has("twakeresourceowner")).isFalse();
    }

    @Test
    void eventActorShouldDefaultToTheCalendarOwnerWithoutConnectedUser() {
        UserActivity activity = handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/%s/calendar/event.ics"}
            """.formatted(alice.id().value())).orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(ALICE);
    }

    @Test
    void eventActorShouldDefaultToTheCalendarOwnerWhenTheConnectedUserIsTechnical() {
        UserActivity activity = handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/%s/calendar/event.ics", "connectedUser": "principals/technicalUser"}
            """.formatted(alice.id().value())).orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(ALICE);
    }

    @Test
    void contactActorShouldDefaultToTheAddressBookOwnerWithoutConnectedUser() {
        UserActivity activity = handle(UserActivities.CONTACT_CREATED_EXCHANGE, contact(alice, null)).orElseThrow();

        assertThat(activity.actors().actor()).isEqualTo(ALICE);
    }

    @Test
    void creationWithoutAKnownUserShouldPublishNothing() {
        assertThat(handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/unknown/calendar/event.ics", "connectedUser": "principals/users/unknown"}
            """)).isEmpty();
    }

    @Test
    void domainAddressBookContactWithoutConnectedUserShouldPublishNothing() {
        assertThat(handle(UserActivities.CONTACT_CREATED_EXCHANGE, """
            {"path": "addressbooks/domain/domain-members/card.vcf", "owner": "principals/domains/domain", "etag": "1",
             "carddata": "", "syncToken": 1}
            """)).isEmpty();
    }

    @Test
    void importedEventShouldPublishNothing() {
        assertThat(handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/calendars/%s/calendar/event.ics", "import": true}
            """.formatted(alice.id().value()))).isEmpty();
    }

    @Test
    void mirrorCopyOfAContactShouldPublishNothing() {
        assertThat(handle(UserActivities.CONTACT_CREATED_EXCHANGE, """
            {"path": "addressbooks/%s/subscribed/card.vcf", "owner": "principals/users/%s", "etag": "1",
             "carddata": "", "syncToken": 1, "sourcePath": "addressbooks/%s/contacts/card.vcf"}
            """.formatted(bob.id().value(), bob.id().value(), alice.id().value()))).isEmpty();
    }

    @Test
    void redeliveredMessageShouldKeepItsId() {
        String message = contact(alice, null);

        assertThat(handle(UserActivities.CONTACT_CREATED_EXCHANGE, message).orElseThrow().id())
            .isEqualTo(handle(UserActivities.CONTACT_CREATED_EXCHANGE, message).orElseThrow().id());
    }

    @Test
    void distinctChangesShouldHaveDistinctIds() {
        String first = """
            {"eventPath": "/calendars/%s/calendar/event.ics", "etag": "1"}
            """.formatted(alice.id().value());
        String recreated = """
            {"eventPath": "/calendars/%s/calendar/event.ics", "etag": "2"}
            """.formatted(alice.id().value());

        assertThat(handle(UserActivities.EVENT_CREATED_EXCHANGE, first).orElseThrow().id())
            .isNotEqualTo(handle(UserActivities.EVENT_CREATED_EXCHANGE, recreated).orElseThrow().id());
    }

    @Test
    void unreadableMessageShouldFail() {
        assertThatThrownBy(() -> handle(UserActivities.EVENT_CREATED_EXCHANGE, "not json"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unexpectedPathShouldFail() {
        assertThatThrownBy(() -> handle(UserActivities.EVENT_CREATED_EXCHANGE, """
            {"eventPath": "/addressbooks/a/b/c.vcf"}
            """))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationShouldDefaultToTheActivityExchange() {
        assertThat(TwakeActivityConfiguration.from(new PropertiesConfiguration()))
            .isEqualTo(new TwakeActivityConfiguration("activity"));
    }

    @Test
    void configurationShouldReadTheConfiguredExchange() {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.addProperty("twake.activity.exchange", "custom-activity");

        assertThat(TwakeActivityConfiguration.from(configuration))
            .isEqualTo(new TwakeActivityConfiguration("custom-activity"));
    }

    private Optional<UserActivity> handle(String exchange, String body) {
        return testee.handle(exchange, body.getBytes(StandardCharsets.UTF_8)).blockOptional();
    }

    private static String contact(OpenPaaSUser owner, String connectedUser) {
        String connectedUserField = Optional.ofNullable(connectedUser)
            .map(value -> ", \"connectedUser\": \"" + value + "\"")
            .orElse("");
        return """
            {"path": "addressbooks/%1$s/contacts/card.vcf", "owner": "principals/users/%1$s", "etag": "\\"1\\"",
             "carddata": "BEGIN:VCARD", "syncToken": 1%2$s}
            """.formatted(owner.id().value(), connectedUserField);
    }
}
