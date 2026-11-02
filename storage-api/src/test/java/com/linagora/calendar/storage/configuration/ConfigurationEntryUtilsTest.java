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

package com.linagora.calendar.storage.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.node.TextNode;

class ConfigurationEntryUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "vi", "ru", "de"})
    void keepLastValuePerKeyShouldKeepTheLastValueOfARepeatedKey(String lastValue) {
        Stream<ConfigurationEntry> entries = Stream.of(
            ConfigurationEntry.of("core", "language", TextNode.valueOf("fr")),
            ConfigurationEntry.of("core", "language", TextNode.valueOf("vi")),
            ConfigurationEntry.of("core", "language", TextNode.valueOf(lastValue)));

        assertThat(ConfigurationEntryUtils.keepLastValuePerKey(entries))
            .containsExactly(ConfigurationEntry.of("core", "language", TextNode.valueOf(lastValue)));
    }

    @Test
    void keepLastValuePerKeyShouldKeepDistinctKeys() {
        Stream<ConfigurationEntry> entries = Stream.of(
            ConfigurationEntry.of("core", "language", TextNode.valueOf("fr")),
            ConfigurationEntry.of("calendar", "language", TextNode.valueOf("en")),
            ConfigurationEntry.of("core", "timezone", TextNode.valueOf("UTC")));

        assertThat(ConfigurationEntryUtils.keepLastValuePerKey(entries))
            .containsExactlyInAnyOrder(
                ConfigurationEntry.of("core", "language", TextNode.valueOf("fr")),
                ConfigurationEntry.of("calendar", "language", TextNode.valueOf("en")),
                ConfigurationEntry.of("core", "timezone", TextNode.valueOf("UTC")));
    }

    @Test
    void mergeShouldLetIncomingOverrideExisting() {
        Set<ConfigurationEntry> existing = Set.of(
            ConfigurationEntry.of("core", "language", TextNode.valueOf("fr")),
            ConfigurationEntry.of("core", "timezone", TextNode.valueOf("UTC")));
        Set<ConfigurationEntry> incoming = Set.of(
            ConfigurationEntry.of("core", "language", TextNode.valueOf("en")));

        assertThat(ConfigurationEntryUtils.mergeIncomingWithExistingConfiguration(incoming, existing))
            .containsExactlyInAnyOrder(
                ConfigurationEntry.of("core", "language", TextNode.valueOf("en")),
                ConfigurationEntry.of("core", "timezone", TextNode.valueOf("UTC")));
    }
}
