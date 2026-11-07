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

package com.linagora.calendar.saas.drive.configuration;

import java.net.URI;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;

public final class TDriveEndpointValidation {
    private TDriveEndpointValidation() {
    }

    static void validateHttpUrl(URI uri, String key) {
        Preconditions.checkArgument(uri != null && uri.getHost() != null && uri.getUserInfo() == null
            && ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())),
            key + " must be an absolute HTTP(S) URL without credentials");
    }

    public static boolean validBearerToken(String token) {
        // Authorization headers carry a single printable ASCII credential.
        return token != null && !token.isEmpty() && CharMatcher.inRange('!', '~').matchesAllOf(token);
    }
}
