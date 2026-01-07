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
import org.apache.james.events.RegistrationKey;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

public record AddressBookURLRegistrationKey(AddressBookURL addressBookURL) implements RegistrationKey {
    public static final String KEY_PREFIX = "addressbook";
    private static final String DELIMITER = ":";
    private static final Splitter DELIMITER_SPLITTER = Splitter.on(DELIMITER).limit(2);

    public static AddressBookURLRegistrationKey fromString(String asString) {
        Preconditions.checkArgument(StringUtils.isNoneEmpty(asString), "RegistrationKey string must not be null");
        Preconditions.checkArgument(asString.startsWith(KEY_PREFIX + DELIMITER), "Invalid AddressBookURLRegistrationKey: %s", asString);

        String withoutPrefix = asString.substring((KEY_PREFIX + DELIMITER).length());
        List<String> parts = DELIMITER_SPLITTER.splitToList(withoutPrefix);
        Preconditions.checkArgument(parts.size() == 2, "Invalid AddressBookURLRegistrationKey format: %s", asString);

        OpenPaaSId base = new OpenPaaSId(parts.get(0));
        String addressBookId = parts.get(1);

        return new AddressBookURLRegistrationKey(new AddressBookURL(base, addressBookId));
    }

    @Override
    public String asString() {
        return KEY_PREFIX + DELIMITER + addressBookURL.baseId().value() + DELIMITER + addressBookURL.addressBookId();
    }
}
