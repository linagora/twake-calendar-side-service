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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience.Domain;

class CommonContactOutboundEventSerializeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeContactDataAndAudience() throws Exception {
        ObjectNode jsContact = OBJECT_MAPPER.readValue("""
            { "version": "1.0", "uid": "contact-uid", "name": { "full": "Alice" } }
            """, ObjectNode.class);
        CommonContactOutboundEvent event = new CommonContactOutboundEvent(new CommonContactOutboundEvent.Audience.User(Username.of("alice@linagora.com")), CommonContactOutboundEvent.Action.ADD,
            URI.create("addressbooks/alice/collected"), new ContactUid("contact-uid"),
            jsContact);

        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        assertThatJson(actual).isEqualTo("""
            {
              "audience": { "user": "alice@linagora.com" },
              "action": "ADD",
              "path": "addressbooks/alice/collected",
              "uid": "contact-uid",
              "payload": {
                "version": "1.0",
                "uid": "contact-uid",
                "name": { "full": "Alice" }
              }
            }
            """);
    }

    @Test
    void shouldOmitContactDataAndKeepEmptyAudience() throws Exception {
        CommonContactOutboundEvent event = new CommonContactOutboundEvent(new CommonContactOutboundEvent.Audience.Unknown(), CommonContactOutboundEvent.Action.DELETE,
            URI.create("addressbooks/alice/collected"), new ContactUid("contact-uid"), null);

        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        assertThatJson(actual).isEqualTo("""
            {
              "audience": {},
              "action": "DELETE",
              "path": "addressbooks/alice/collected",
              "uid": "contact-uid"
            }
            """);
    }

    @Test
    void shouldSerializeDomainAudience() throws Exception {
        CommonContactOutboundEvent event = new CommonContactOutboundEvent(new Domain(org.apache.james.core.Domain.of("linagora.com")), CommonContactOutboundEvent.Action.ADD,
            URI.create("addressbooks/domain/domain-members/contact-uid.vcf"), new ContactUid("contact-uid"), null);

        assertThatJson(new String(event.serialize(), StandardCharsets.UTF_8)).isEqualTo("""
            {
              "audience": { "domain": "linagora.com" },
              "action": "ADD",
              "path": "addressbooks/domain/domain-members/contact-uid.vcf",
              "uid": "contact-uid"
            }
            """);
    }
}
