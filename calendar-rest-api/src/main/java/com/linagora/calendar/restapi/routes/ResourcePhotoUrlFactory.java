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
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;

public class ResourcePhotoUrlFactory {
    private final Function<String, URI> buildURLFunction;
    private static final CharMatcher ALLOWED_ICON_CHARS = CharMatcher.inRange('a', 'z')
        .or(CharMatcher.inRange('A', 'Z'))
        .or(CharMatcher.inRange('0', '9'))
        .or(CharMatcher.anyOf("_-"))
        .precomputed();

    @Singleton
    @Inject
    public ResourcePhotoUrlFactory(@Named("spaCalendarUrl") URL baseUrl) {
        this.buildURLFunction = resourceIconName
            -> URI.create(Strings.CI.removeEnd(baseUrl.toString(), "/") + "/linagora.esn.resource/images/icon/" + resourceIconName + ".svg");
    }

    public URI resolveURL(String iconName) {
        Preconditions.checkArgument(StringUtils.isNotBlank(iconName) && ALLOWED_ICON_CHARS.matchesAllOf(iconName),
            "iconName must contain only alphanumeric, hyphen, or underscore characters");
        return buildURLFunction.apply(iconName);
    }
}
