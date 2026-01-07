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

import jakarta.inject.Inject;

import com.linagora.calendar.storage.AddressBookURLRegistrationKey;

public class AddressBookURLRegistrationKeyFactory implements RegistrationKey.Factory {

    @Inject
    public AddressBookURLRegistrationKeyFactory() {
    }

    @Override
    public Class<? extends RegistrationKey> forClass() {
        return AddressBookURLRegistrationKey.class;
    }

    @Override
    public RegistrationKey fromString(String asString) {
        return AddressBookURLRegistrationKey.fromString(asString);
    }
}
