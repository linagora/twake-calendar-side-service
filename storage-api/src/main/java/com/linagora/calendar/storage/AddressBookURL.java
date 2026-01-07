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
import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

public record AddressBookURL(OpenPaaSId baseId, String addressBookId) {
    public static final String ADDRESS_BOOKS_SEGMENT = "addressbooks";
    public static final String ADDRESS_BOOK_URL_PATH_PREFIX = "/" + ADDRESS_BOOKS_SEGMENT;

    /**
     * <p>
     * Supported inputs:
     * - baseId/addressBookId
     * - /baseId/addressBookId
     * - /addressbooks/baseId/addressBookId
     * - /addressbooks/baseId/addressBookId/
     */
    public static AddressBookURL parse(String raw) {
        Preconditions.checkArgument(StringUtils.isNotBlank(raw), "AddressBookURL raw value must not be null or empty");

        List<String> parts = Splitter.on('/')
            .omitEmptyStrings()
            .trimResults()
            .splitToList(raw);

        Preconditions.checkArgument(!parts.isEmpty(), "Invalid AddressBookURL format: %s", raw);

        if (ADDRESS_BOOKS_SEGMENT.equals(parts.getFirst())) {
            Preconditions.checkArgument(parts.size() >= 3, "Invalid AddressBookURL format, expected /addressbooks/{baseId}/{addressBookId}: %s", raw);
            return new AddressBookURL(new OpenPaaSId(parts.get(1)), parts.get(2));
        }

        Preconditions.checkArgument(parts.size() >= 2, "Invalid AddressBookURL format, expected {baseId}/{addressBookId}: %s", raw);
        return new AddressBookURL(new OpenPaaSId(parts.get(0)), parts.get(1));
    }

    public AddressBookURL {
        Preconditions.checkArgument(baseId != null, "baseId must not be null");
        Preconditions.checkArgument(StringUtils.isNoneEmpty(addressBookId), "addressBookId must not be empty");
    }

    public URI asUri() {
        return URI.create(ADDRESS_BOOK_URL_PATH_PREFIX + "/" + baseId.value() + "/" + addressBookId);
    }
}
