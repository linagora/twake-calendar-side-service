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

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.events.RegistrationKey;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

public record UsernameRegistrationKey(Username username) implements RegistrationKey {

    public static final String KEY_PREFIX = "username";
    private static final String DELIMITER = ":";

    public static UsernameRegistrationKey fromString(String asString) {
        Preconditions.checkArgument(StringUtils.isNoneEmpty(asString), "RegistrationKey string must not be null");
        Preconditions.checkArgument(asString.startsWith(KEY_PREFIX + DELIMITER), "Invalid UsernameRegistrationKey: %s", asString);
        List<String> parts = Splitter.on(DELIMITER)
            .omitEmptyStrings().trimResults()
            .splitToList(asString);
        Preconditions.checkArgument(parts.size() == 2, "Invalid UsernameRegistrationKey format: %s", asString);
        return new UsernameRegistrationKey(Username.of(parts.get(1)));
    }

    @Override
    public String asString() {
        return KEY_PREFIX + DELIMITER + username.asString();
    }
}
