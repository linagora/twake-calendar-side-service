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

import java.net.URI;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;

public record AddressBookURL(OpenPaaSId base, String addressBookId) {
    public static final String ADDRESS_BOOK_URL_PATH_PREFIX = "/addressbooks";

    public AddressBookURL {
        Preconditions.checkArgument(base != null, "baseCalendarId must not be null");
        Preconditions.checkArgument(StringUtils.isNoneEmpty(addressBookId), "addressBookId must not be empty");
    }

    public URI asUri() {
        return URI.create(ADDRESS_BOOK_URL_PATH_PREFIX + "/" + base.value() + "/" + addressBookId);
    }
}
