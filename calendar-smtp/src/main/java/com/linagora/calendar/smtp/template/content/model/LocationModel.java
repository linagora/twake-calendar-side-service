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

package com.linagora.calendar.smtp.template.content.model;

import java.net.URI;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

public record LocationModel(String value) {

    public Map<String, Object> toPugModel() {
        return Map.of(
            "value", value,
            "isValidURL", isValidURL(),
            "isAbsoluteURL", isAbsoluteURL());
    }

    private boolean isValidURL() {
        if (StringUtils.isBlank(value)) {
            return false;
        }

        try {
            URI.create(value).toURL();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isAbsoluteURL() {
        if (StringUtils.isBlank(value)) {
            return false;
        }

        try {
            URI uri = new URI(value);
            return uri.isAbsolute();
        } catch (Exception e) {
            return false;
        }
    }
}
