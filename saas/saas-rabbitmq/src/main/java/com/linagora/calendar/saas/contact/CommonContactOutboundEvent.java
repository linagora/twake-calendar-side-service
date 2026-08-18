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
import java.net.URI;

import org.apache.james.core.Username;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.linagora.calendar.dav.ContactUid;

public record CommonContactOutboundEvent(Audience audience,
                                         Action action,
                                         URI path,
                                         @JsonIgnore ContactUid uid,
                                         @JsonInclude(JsonInclude.Include.NON_NULL) JsonNode payload) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public byte[] serialize() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }

    @JsonGetter("uid")
    public String uidAsString() {
        return uid.value();
    }

    public enum Action {
        ADD, UPDATE, DELETE
    }

    @JsonSerialize(using = AudienceSerializer.class)
    public sealed interface Audience permits Audience.User, Audience.Domain, Audience.Unknown {

        record User(Username username) implements Audience {
        }

        record Domain(org.apache.james.core.Domain domain) implements Audience {
        }

        record Unknown() implements Audience {
        }
    }

    public static class AudienceSerializer extends JsonSerializer<Audience> {
        @Override
        public void serialize(Audience audience, JsonGenerator generator, SerializerProvider serializers) throws IOException {
            generator.writeStartObject();
            switch (audience) {
                case Audience.User user -> generator.writeStringField("user", user.username().asString());
                case Audience.Domain domain -> generator.writeStringField("domain", domain.domain().asString());
                case Audience.Unknown ignored -> {
                }
            }
            generator.writeEndObject();
        }
    }
}
