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

package com.linagora.calendar.dav;

import java.util.Arrays;
import java.util.Optional;

public enum DavRight {
    READ("dav:read", 2),
    READ_WRITE("dav:read-write", 3),
    ADMINISTRATION("dav:administration", 5);

    private final String value;
    private final int access;

    DavRight(String value, int access) {
        this.value = value;
        this.access = access;
    }

    public String value() {
        return value;
    }

    public int access() {
        return access;
    }

    public static Optional<DavRight> fromAccess(int access) {
        return Arrays.stream(values())
            .filter(right -> right.access == access)
            .findFirst();
    }

    public static DavRight fromValue(String value) {
        return Arrays.stream(values())
            .filter(right -> right.value.equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported DAV right: " + value));
    }
}
