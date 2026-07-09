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

package com.linagora.calendar.storage.booking;

import java.util.Optional;
import java.util.regex.Pattern;

import com.google.common.base.Preconditions;

public class BookingLinkColorUtil {

    private static final Pattern COLOR_PATTERN = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public static Optional<String> sanitize(Optional<String> raw) {
        return raw.map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(BookingLinkColorUtil::validate);
    }

    public static String validate(String color) {
        Preconditions.checkArgument(COLOR_PATTERN.matcher(color).matches(),
            "'color' must be a valid hex color (e.g. %s)", BookingLink.DEFAULT_COLOR);
        return color;
    }

    private BookingLinkColorUtil() {
    }
}
