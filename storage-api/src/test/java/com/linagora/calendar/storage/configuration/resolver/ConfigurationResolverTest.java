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

package com.linagora.calendar.storage.configuration.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

import reactor.core.publisher.Flux;

class ConfigurationResolverTest {
    private static final ModuleName CORE = new ModuleName("core");
    private static final ConfigurationKey BUSINESS_HOURS = new ConfigurationKey("businessHours");
    private static final ConfigurationKey LANGUAGE = new ConfigurationKey("language");
    private static final EntryIdentifier BUSINESS_HOURS_ID = new EntryIdentifier(CORE, BUSINESS_HOURS);
    private static final EntryIdentifier LANGUAGE_ID = new EntryIdentifier(CORE, LANGUAGE);
    private static final MailboxSession SESSION = MailboxSessionUtil.create(Username.of("alice@example.com"));

    private static final ConfigurationEntryResolver STORED_WITH_NULL_VALUE = new ConfigurationEntryResolver() {
        @Override
        public Flux<ConfigurationEntry> resolve(Set<EntryIdentifier> ids, MailboxSession session) {
            return Flux.just(new ConfigurationEntry(CORE, BUSINESS_HOURS, null),
                new ConfigurationEntry(CORE, LANGUAGE, TextNode.valueOf("fr")));
        }

        @Override
        public Set<EntryIdentifier> entryIdentifiers() {
            return Set.of(BUSINESS_HOURS_ID, LANGUAGE_ID);
        }
    };

    private final ConfigurationResolver testee = new ConfigurationResolver(Set.of(STORED_WITH_NULL_VALUE),
        new FallbackConfigurationEntryResolver.EmptyFallbackConfigurationEntryResolver());

    @Test
    void resolveShouldSupportStoredNullValues() {
        ConfigurationDocument document = testee.resolve(Set.of(BUSINESS_HOURS_ID, LANGUAGE_ID), SESSION).block();

        assertThat(document.table().get(CORE, LANGUAGE)).isEqualTo(TextNode.valueOf("fr"));
        assertThat(document.table().get(CORE, BUSINESS_HOURS)).isEqualTo(NullNode.getInstance());
    }

    @Test
    void resolveAllShouldSupportStoredNullValues() {
        ConfigurationDocument document = testee.resolveAll(SESSION).block();

        assertThat(document.table().get(CORE, LANGUAGE)).isEqualTo(TextNode.valueOf("fr"));
        assertThat(document.table().get(CORE, BUSINESS_HOURS)).isEqualTo(NullNode.getInstance());
    }
}
