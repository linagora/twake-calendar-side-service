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

package com.linagora.calendar.dav.importer;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * Imported items are stored on the DAV server under a resource name derived from their UID. Exotic UIDs
 * would yield an invalid - possibly escaping - path, thus a random name is used instead for those.
 */
public final class DavResourceName {

    private static final Pattern SAFE_RESOURCE_NAME = Pattern.compile("[\\w.~:@%+-]{1,200}");

    public static String fromUid(String uid) {
        return Optional.ofNullable(uid)
            .map(StringUtils::trimToNull)
            .filter(value -> SAFE_RESOURCE_NAME.matcher(value).matches())
            .orElseGet(() -> UUID.randomUUID().toString());
    }

    private DavResourceName() {
    }
}
