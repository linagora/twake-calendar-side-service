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

import com.google.common.base.Preconditions;

public record ClouderyLookup(URI url, String token) {
    static final String URL_PROPERTY = "twp.drive.lookup.url";
    static final String TOKEN_PROPERTY = "twp.drive.lookup.token";

    public ClouderyLookup {
        TDriveEndpointValidation.validateHttpUrl(url, URL_PROPERTY);
        Preconditions.checkArgument(url.getFragment() == null, "%s must not contain a fragment", URL_PROPERTY);
        Preconditions.checkArgument(url.getRawQuery() == null, "%s must not contain a query", URL_PROPERTY);
        Preconditions.checkArgument(TDriveEndpointValidation.validBearerToken(token),
            "%s must be a non-blank bearer token", TOKEN_PROPERTY);
    }

    @Override
    public String toString() {
        return "ClouderyLookup[token=REDACTED]";
    }
}
