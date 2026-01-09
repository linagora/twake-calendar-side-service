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

package org.apache.james.events;

import java.util.List;

import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.linagora.calendar.storage.UsernameRegistrationKey;

public class UsernameRegistrationKeyFactory implements RegistrationKey.Factory {

    @Override
    public Class<? extends RegistrationKey> forClass() {
        return UsernameRegistrationKey.class;
    }

    @Override
    public RegistrationKey fromString(String asString) {
        return new UsernameRegistrationKey(parse(asString));
    }

    private Username parse(String asString) {
        List<String> parts = Splitter.on(':')
            .omitEmptyStrings().trimResults()
            .splitToList(asString);
        Preconditions.checkArgument(parts.size() == 2, "Invalid UsernameRegistrationKey format: %s", asString);
        return Username.of(parts.get(1));
    }
}
