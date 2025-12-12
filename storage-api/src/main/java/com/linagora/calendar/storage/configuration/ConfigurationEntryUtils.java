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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.tuple.Pair;

public class ConfigurationEntryUtils {

    public static final Set<ConfigurationEntry> EMPTY_CONFIGURATION_ENTRIES = Set.of();

    public static Set<ConfigurationEntry> mergeIncomingWithExistingConfiguration(Set<ConfigurationEntry> incoming, Set<ConfigurationEntry> existing) {
        return mergeIncomingWithExistingConfiguration(incoming.stream(), existing.stream());
    }

    public static Set<ConfigurationEntry> mergeIncomingWithExistingConfiguration(Stream<ConfigurationEntry> incoming, Stream<ConfigurationEntry> existing) {
        return Stream.concat(existing, incoming)
            .collect(Collectors.toMap(entry -> Pair.of(entry.moduleName(), entry.configurationKey()),
                entry -> entry,
                (oldVal, newVal) -> newVal))
            .values()
            .stream()
            .collect(Collectors.toUnmodifiableSet());
    }

}