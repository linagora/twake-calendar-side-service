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

public enum UserSearchMode {
    DISABLED, LIMITED, ENABLED;

    public String serialize() {
        return name().toLowerCase();
    }

    public static UserSearchMode deserialize(String value) {
        return switch (value.toLowerCase()) {
            case "disabled" -> DISABLED;
            case "limited" -> LIMITED;
            case "enabled" -> ENABLED;
            default -> throw new IllegalArgumentException("Unknown userSearchMode: " + value);
        };
    }
}
