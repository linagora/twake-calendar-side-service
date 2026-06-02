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

public record DomainSettings(Optional<UserSearchMode> userSearchMode,
                             Optional<Boolean> resourceSearchEnabled,
                             Optional<DefaultCalendarPublicVisibility> defaultCalendarPublicVisibility) {
    public static final UserSearchMode DEFAULT_USER_SEARCH_MODE = UserSearchMode.ENABLED;
    public static final boolean DEFAULT_RESOURCE_SEARCH_ENABLED = true;
    public static final DefaultCalendarPublicVisibility DEFAULT_CALENDAR_PUBLIC_VISIBILITY = DefaultCalendarPublicVisibility.PRIVATE;
    public static final DomainSettings DEFAULT_DOMAIN_SETTINGS = new DomainSettings(Optional.empty(), Optional.empty(), Optional.empty());

    public static class Builder {
        private Optional<UserSearchMode> userSearchMode = Optional.empty();
        private Optional<Boolean> resourceSearchEnabled = Optional.empty();
        private Optional<DefaultCalendarPublicVisibility> defaultCalendarPublicVisibility = Optional.empty();

        public Builder userSearchMode(UserSearchMode userSearchMode) {
            this.userSearchMode = Optional.of(userSearchMode);
            return this;
        }

        public Builder resourceSearchEnabled(boolean resourceSearchEnabled) {
            this.resourceSearchEnabled = Optional.of(resourceSearchEnabled);
            return this;
        }

        public Builder defaultCalendarPublicVisibility(DefaultCalendarPublicVisibility defaultCalendarPublicVisibility) {
            this.defaultCalendarPublicVisibility = Optional.of(defaultCalendarPublicVisibility);
            return this;
        }

        public DomainSettings build() {
            return new DomainSettings(userSearchMode, resourceSearchEnabled, defaultCalendarPublicVisibility);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
