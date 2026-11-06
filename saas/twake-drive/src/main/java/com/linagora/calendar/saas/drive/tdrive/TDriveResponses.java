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

package com.linagora.calendar.saas.drive.tdrive;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

final class TDriveResponses {
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Token(String token) {
        @Override
        public String toString() {
            return "Token[REDACTED]";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(Resource data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Resource(String id, Attributes attributes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attributes(String type, Map<String, String> shortcodes) {
    }
}
