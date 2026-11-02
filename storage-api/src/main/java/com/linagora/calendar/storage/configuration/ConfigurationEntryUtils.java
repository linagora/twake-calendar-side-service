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

import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ConfigurationEntryUtils {

    public static final Set<ConfigurationEntry> EMPTY_CONFIGURATION_ENTRIES = Set.of();

    public static Set<ConfigurationEntry> mergeIncomingWithExistingConfiguration(Set<ConfigurationEntry> incoming, Set<ConfigurationEntry> existing) {
        return mergeIncomingWithExistingConfiguration(incoming.stream(), existing.stream());
    }

    public static Set<ConfigurationEntry> mergeIncomingWithExistingConfiguration(Stream<ConfigurationEntry> incoming, Stream<ConfigurationEntry> existing) {
        return keepLastValuePerKey(Stream.concat(existing, incoming));
    }

    /**
     * Keeps a single entry per (module, key): when a key is repeated, the last one in encounter order wins.
     * The given stream must therefore be ordered (e.g. built from a list, not from a hash set).
     */
    public static Set<ConfigurationEntry> keepLastValuePerKey(Stream<ConfigurationEntry> entries) {
        return entries
            .collect(Collectors.toMap(ConfigurationEntry::getEntryIdentifier,
                Function.identity(),
                (oldVal, newVal) -> newVal))
            .values()
            .stream()
            .collect(Collectors.toUnmodifiableSet());
    }

}