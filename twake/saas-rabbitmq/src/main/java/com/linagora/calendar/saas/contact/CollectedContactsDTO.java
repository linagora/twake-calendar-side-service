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
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CollectedContactsDTO(@JsonProperty(required = true) String userEmail,
                                   @JsonProperty(required = true) List<ObjectNode> collectedContacts) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static CollectedContactsDTO deserialize(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, CollectedContactsDTO.class);
        } catch (IOException e) {
            throw new CollectedContactsDeserializeException("Unable to deserialize collected contacts message", e);
        }
    }
}
