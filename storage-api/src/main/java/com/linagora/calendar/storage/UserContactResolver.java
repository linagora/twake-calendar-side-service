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

import java.util.Optional;

import org.apache.james.core.Username;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.UserNameResolver.UserNames;

import reactor.core.publisher.Mono;

public interface UserContactResolver {
    /**
     * @param names the first and last name stored with the user, as {@link UserNameResolver} resolves them
     */
    record UserContact(Optional<UserNames> names,
                       Optional<String> givenName,
                       Optional<String> familyName,
                       Optional<String> displayName,
                       Optional<String> telephoneNumber,
                       ImmutableMap<String, String> extraProperties) {

        public static class Builder {
            private Optional<UserNames> names = Optional.empty();
            private Optional<String> givenName = Optional.empty();
            private Optional<String> familyName = Optional.empty();
            private Optional<String> displayName = Optional.empty();
            private Optional<String> telephoneNumber = Optional.empty();
            private ImmutableMap<String, String> extraProperties = ImmutableMap.of();

            public Builder names(Optional<UserNames> names) {
                this.names = names;
                return this;
            }

            public Builder givenName(Optional<String> givenName) {
                this.givenName = givenName;
                return this;
            }

            public Builder familyName(Optional<String> familyName) {
                this.familyName = familyName;
                return this;
            }

            public Builder displayName(Optional<String> displayName) {
                this.displayName = displayName;
                return this;
            }

            public Builder telephoneNumber(Optional<String> telephoneNumber) {
                this.telephoneNumber = telephoneNumber;
                return this;
            }

            public Builder extraProperties(ImmutableMap<String, String> extraProperties) {
                this.extraProperties = extraProperties;
                return this;
            }

            public UserContact build() {
                return new UserContact(names, givenName, familyName, displayName, telephoneNumber, extraProperties);
            }
        }

        public static Builder builder() {
            return new Builder();
        }
    }

    /**
     * Resolves the details of a user that the domain member card carries, read from the user directory.
     * Empty when the directory does not know the user. Directory failures are propagated.
     */
    Mono<Optional<UserContact>> resolve(Username username);

    class Noop implements UserContactResolver {
        @Override
        public Mono<Optional<UserContact>> resolve(Username username) {
            return Mono.just(Optional.empty());
        }
    }
}
