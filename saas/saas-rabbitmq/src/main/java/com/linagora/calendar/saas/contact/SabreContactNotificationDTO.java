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

package com.linagora.calendar.saas.contact;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SabreContactNotificationDTO(@JsonProperty(required = true) String path,
                                          @JsonProperty(required = true) String owner,
                                          @JsonProperty(required = true) String carddata) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static SabreContactNotificationDTO deserialize(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, SabreContactNotificationDTO.class);
        } catch (IOException e) {
            throw new CommonContactNotificationDeserializeException("Unable to deserialize Sabre contact notification", e);
        }
    }
}
