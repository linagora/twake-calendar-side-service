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

import com.linagora.calendar.storage.UsernameRegistrationKey;

public class UsernameRegistrationKeyFactory implements RegistrationKey.Factory {

    @Override
    public Class<? extends RegistrationKey> forClass() {
        return UsernameRegistrationKey.class;
    }

    @Override
    public RegistrationKey fromString(String asString) {
        return UsernameRegistrationKey.fromString(asString);
    }
}
