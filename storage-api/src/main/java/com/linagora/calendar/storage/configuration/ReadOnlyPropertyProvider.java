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

import java.util.Objects;
import java.util.Set;

public interface ReadOnlyPropertyProvider {

    Set<EntryIdentifier> readOnlySettings();

    static ReadOnlyPropertyProvider of(EntryIdentifier... entries) {
        Objects.requireNonNull(entries, "entries must not be null");
        return new ListBasedReadOnlyPropertyProvider(Set.of(entries));
    }

    record EmptyReadOnlyPropertyProvider() implements ReadOnlyPropertyProvider {
        @Override
        public Set<EntryIdentifier> readOnlySettings() {
            return Set.of();
        }
    }

    record ListBasedReadOnlyPropertyProvider(Set<EntryIdentifier> readOnlySettings) implements ReadOnlyPropertyProvider {

        public ListBasedReadOnlyPropertyProvider(Set<EntryIdentifier> readOnlySettings) {
            this.readOnlySettings = Set.copyOf(readOnlySettings);
        }
    }
}
