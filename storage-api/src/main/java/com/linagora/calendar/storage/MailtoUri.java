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

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.Preconditions;

public final class MailtoUri {
    private static final String MAILTO_PREFIX = "mailto:";

    public static boolean hasMailtoPrefix(String value) {
        if (value == null) {
            return false;
        }
        return Strings.CI.startsWith(StringUtils.strip(value.trim(), "<>"), MAILTO_PREFIX);
    }

    public static String stripMailtoPrefix(String value) {
        Preconditions.checkNotNull(value, "value must not be null");

        String sanitized = StringUtils.strip(value.trim(), "<>");
        if (hasMailtoPrefix(sanitized)) {
            sanitized = sanitized.substring(MAILTO_PREFIX.length());
        }
        return StringUtils.strip(sanitized, "<>");
    }

    private MailtoUri() {
    }
}
