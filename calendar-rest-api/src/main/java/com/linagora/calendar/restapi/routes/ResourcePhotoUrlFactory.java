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

package com.linagora.calendar.restapi.routes;

import java.net.URI;
import java.net.URL;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.linagora.calendar.api.DomainUrl;
import com.linagora.calendar.restapi.RestApiConfiguration;

public class ResourcePhotoUrlFactory {
    private final DomainUrl baseUrl;
    private static final CharMatcher ALLOWED_ICON_CHARS = CharMatcher.inRange('a', 'z')
        .or(CharMatcher.inRange('A', 'Z'))
        .or(CharMatcher.inRange('0', '9'))
        .or(CharMatcher.anyOf("_-"))
        .precomputed();

    @Singleton
    @Inject
    public ResourcePhotoUrlFactory(RestApiConfiguration restApiConfiguration) {
        this(restApiConfiguration.getSelfUrl());
    }

    public ResourcePhotoUrlFactory(URL baseUrl) {
        this(DomainUrl.of(baseUrl));
    }

    public ResourcePhotoUrlFactory(DomainUrl baseUrl) {
        this.baseUrl = baseUrl;
    }

    public URI resolveURL(String iconName, Username user) {
        Preconditions.checkArgument(StringUtils.isNotBlank(iconName) && ALLOWED_ICON_CHARS.matchesAllOf(iconName),
            "iconName must contain only alphanumeric, hyphen, or underscore characters");
        return URI.create(Strings.CI.removeEnd(baseUrl.resolve(user).toString(), "/") + "/images/icon/" + iconName + ".svg");
    }
}
