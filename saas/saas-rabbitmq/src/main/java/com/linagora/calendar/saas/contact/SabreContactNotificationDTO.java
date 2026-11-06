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
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

/**
 * Sabre publishes one notification for the address book holding the contact, and one for each address book
 * subscribed to or delegated from it: the latter carry the {@code sourcePath} of the contact they mirror.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SabreContactNotificationDTO(@JsonProperty(required = true) String path,
                                          @JsonProperty(required = true) String owner,
                                          @JsonProperty(required = true) String carddata,
                                          @JsonProperty(required = true) long syncToken,
                                          @JsonProperty(required = true) String etag,
                                          Optional<String> sourcePath) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    public boolean isMirrorCopy() {
        return sourcePath.isPresent();
    }

    public static SabreContactNotificationDTO deserialize(byte[] body) {
        try {
            return OBJECT_MAPPER.readValue(body, SabreContactNotificationDTO.class);
        } catch (IOException e) {
            throw new CommonContactNotificationDeserializeException("Unable to deserialize Sabre contact notification", e);
        }
    }
}
