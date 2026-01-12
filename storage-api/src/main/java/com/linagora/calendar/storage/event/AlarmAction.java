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

package com.linagora.calendar.storage.event;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public enum AlarmAction {
    EMAIL,
    DISPLAY;

    public static final Set<String> SUPPORTED_VALUES = Arrays.stream(AlarmAction.values()).map(AlarmAction::getValue).collect(Collectors.toSet());

    public static Optional<AlarmAction> fromString(String value) {
        return Arrays.stream(values())
            .filter(action -> action.name().equalsIgnoreCase(value))
            .findFirst();
    }

    public String getValue() {
        return name().toUpperCase(Locale.US);
    }
}
