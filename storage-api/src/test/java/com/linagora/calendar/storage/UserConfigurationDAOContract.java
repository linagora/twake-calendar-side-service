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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.exception.UserNotFoundException;

public interface UserConfigurationDAOContract {
    Domain DOMAIN = Domain.of("domain.tld");
    Username USERNAME = Username.fromLocalPartWithDomain("user", DOMAIN);
    Username USERNAME_2 = Username.fromLocalPartWithDomain("username", DOMAIN);
    MailboxSession MAILBOX_SESSION = MailboxSessionUtil.create(USERNAME);
    MailboxSession MAILBOX_SESSION_2 = MailboxSessionUtil.create(USERNAME_2);
    ObjectMapper MAPPER = new ObjectMapper();

    UserConfigurationDAO testee();

    @Test
    default void persistThenRetrieveShouldReturnSameEntries() {
        Set<ConfigurationEntry> originalEntries = Set.of(ConfigurationEntry.of("core", "textNode", new TextNode("vi")));

        testee().persistConfiguration(originalEntries, MAILBOX_SESSION).block();

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(actual)
            .usingElementComparator(Comparator.comparing(Object::toString))
            .containsExactlyInAnyOrderElementsOf(originalEntries);
    }

    @Test
    default void persistShouldOverridePreviousConfiguration() {
        ConfigurationEntry first = ConfigurationEntry.of("core", "language", new TextNode("vi"));
        ConfigurationEntry updatedEntry = ConfigurationEntry.of("core", "language", new TextNode("en"));

        testee().persistConfiguration(Set.of(first), MAILBOX_SESSION).block();
        testee().persistConfiguration(Set.of(updatedEntry), MAILBOX_SESSION).block();

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(actual)
            .hasSize(1)
                .contains(updatedEntry);
    }

    @Test
    default void persistConfigurationShouldRemoveEntriesNotPresentInUpdate() {
        Set<ConfigurationEntry> initialConfig = Set.of(
            ConfigurationEntry.of("core", "language", new TextNode("vi")),
            ConfigurationEntry.of("core", "timezone", new TextNode("Asia/Ho_Chi_Minh")),
            ConfigurationEntry.of("linagora.esn", "featureX", BooleanNode.TRUE)
        );

        testee().persistConfiguration(initialConfig, MAILBOX_SESSION).block();

        Set<ConfigurationEntry> updatedConfig = Set.of(
            ConfigurationEntry.of("core", "language", new TextNode("en")) // override
        );

        testee().persistConfiguration(updatedConfig, MAILBOX_SESSION).block();

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(actual)
            .usingRecursiveFieldByFieldElementComparator()
            .containsExactlyElementsOf(updatedConfig);
    }


    @Test
    default void persistShouldHandleComplexConfigurations() {
        Set<ConfigurationEntry> originalEntries = Set.of(
            ConfigurationEntry.of("linagora.esn.unifiedinbox", "booleanNode", BooleanNode.TRUE),
            ConfigurationEntry.of("linagora.esn.unifiedinbox", "nullNode", null),
            ConfigurationEntry.of("linagora.esn", "emptyStringNode", toJsonNode("{}")),
            ConfigurationEntry.of("core", "textNode", new TextNode("vi")),
            ConfigurationEntry.of("core", "numberNode", new LongNode(123456789)),
            ConfigurationEntry.of("core", "objectNode", toJsonNode("""
                {
                    "timeZone": "America/Adak",
                    "use24hourFormat": true
                }""")),
            ConfigurationEntry.of("core", "arrayNode", toJsonNode("""
                 [ { "daysOfWeek": [1,2,3,4,5,6], "start": "9:0", "end": "17:0" } ]
                """)));

        testee().persistConfiguration(originalEntries, MAILBOX_SESSION).block();

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(actual)
            .usingElementComparator(Comparator.comparing(Object::toString))
            .containsExactlyInAnyOrderElementsOf(originalEntries);
    }

    @Test
   default void persistConfigurationShouldNotOverrideConfigurationOfAnotherUser() {
        Set<ConfigurationEntry> configA = Set.of(ConfigurationEntry.of("core", "language", new TextNode("vi")));
        Set<ConfigurationEntry> configB = Set.of(ConfigurationEntry.of("core", "language", new TextNode("en")));

        testee().persistConfiguration(configA, MAILBOX_SESSION).block();
        testee().persistConfiguration(configB, MAILBOX_SESSION_2).block();

        List<ConfigurationEntry> actualA = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();
        List<ConfigurationEntry> actualB = testee().retrieveConfiguration(MAILBOX_SESSION_2).collectList().block();

        assertThat(actualA)
            .containsExactlyInAnyOrderElementsOf(configA);

        assertThat(actualB)
            .containsExactlyInAnyOrderElementsOf(configB);
    }

    @Test
    default void persistConfigurationShouldThrowWhenUserDoesNotExist() {
        MailboxSession nonExistentSession = MailboxSessionUtil.create(Username.fromLocalPartWithDomain("username" + UUID.randomUUID(), DOMAIN));

        Set<ConfigurationEntry> config = Set.of(ConfigurationEntry.of("core", "language", new TextNode("vi")));

        assertThatThrownBy(() -> testee().persistConfiguration(config, nonExistentSession).block())
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    default void persistConfigurationShouldRemoveAllEntriesWhenEmptySet() {
        Set<ConfigurationEntry> configA = Set.of(ConfigurationEntry.of("core", "language", new TextNode("vi")));

        testee().persistConfiguration(configA, MAILBOX_SESSION).block();
        testee().persistConfiguration(Set.of(), MAILBOX_SESSION).block();

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(actual)
            .isEmpty();
    }

    @Test
    default void retrieveShouldReturnEmptyWhenNoData() {
        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block();

        Assertions.assertThat(actual).isEmpty();
    }

    @Test
    default void retrieveShouldThrowWhenUserNotFound() {
        assertThatThrownBy(() -> testee().retrieveConfiguration(MailboxSessionUtil.create(Username.fromLocalPartWithDomain(UUID.randomUUID().toString(), DOMAIN)))
            .collectList().block())
            .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    default void retrieveConfigurationShouldBeIdempotent() {
        Set<ConfigurationEntry> config = Set.of(ConfigurationEntry.of("core", "language", new TextNode("vi")));

        testee().persistConfiguration(config, MAILBOX_SESSION).block();

        List<ConfigurationEntry> first = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();
        List<ConfigurationEntry> second = testee().retrieveConfiguration(MAILBOX_SESSION).collectList().block();

        assertThat(first).isEqualTo(second);
    }

    @Test
    default void retrieveConfigurationShouldNotLeakOtherUserData() {
        Set<ConfigurationEntry> configA = Set.of(ConfigurationEntry.of("core", "language", new TextNode("vi")));

        Set<ConfigurationEntry> configB = Set.of(
            ConfigurationEntry.of("core", "language", new TextNode("en")),
            ConfigurationEntry.of("unifiedinbox", "useEmailLinks", BooleanNode.TRUE)
        );

        testee().persistConfiguration(configA, MAILBOX_SESSION).block();
        testee().persistConfiguration(configB, MAILBOX_SESSION_2).block();

        List<ConfigurationEntry> retrievedB = testee().retrieveConfiguration(MAILBOX_SESSION_2).collectList().block();

        assertThat(retrievedB)
            .usingElementComparator(Comparator.comparing(Object::toString))
            .containsExactlyInAnyOrderElementsOf(configB)
            .doesNotContainAnyElementsOf(configA);
    }

    default JsonNode toJsonNode(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize JSON", e);
        }
    }

}