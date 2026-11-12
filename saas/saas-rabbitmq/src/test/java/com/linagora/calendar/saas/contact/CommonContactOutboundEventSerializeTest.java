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
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookDeletedEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncEndEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncStartEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience.Domain;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.ContactChangedEvent;

class CommonContactOutboundEventSerializeTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void shouldSerializeAddressBookDeletionWithoutUidOrPayload() throws Exception {
        CommonContactOutboundEvent event = new AddressBookDeletedEvent(new CommonContactOutboundEvent.Audience.User(Username.of("alice@linagora.com")),
            URI.create("addressbooks/alice/custom"));

        assertThatJson(new String(event.serialize(), StandardCharsets.UTF_8)).isEqualTo("""
            {
              "audience": { "user": "alice@linagora.com" },
              "action": "DELETE",
              "path": "addressbooks/alice/custom"
            }
            """);
    }

    @Test
    void shouldSerializeContactDataAndAudience() throws Exception {
        ObjectNode jsContact = OBJECT_MAPPER.readValue("""
            { "version": "1.0", "uid": "contact-uid", "name": { "full": "Alice" } }
            """, ObjectNode.class);
        CommonContactOutboundEvent event = ContactChangedEvent.builder()
            .audience(new CommonContactOutboundEvent.Audience.User(Username.of("alice@linagora.com")))
            .action(CommonContactOutboundEvent.Action.ADD)
            .path(URI.create("addressbooks/alice/collected"))
            .uid(new ContactUid("contact-uid"))
            .revision(42L)
            .etag("\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\"")
            .payload(jsContact)
            .build();

        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        assertThatJson(actual).isEqualTo("""
            {
              "audience": { "user": "alice@linagora.com" },
              "action": "ADD",
              "path": "addressbooks/alice/collected",
              "uid": "contact-uid",
              "revision": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\"",
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
        CommonContactOutboundEvent event = ContactChangedEvent.builder()
            .audience(new CommonContactOutboundEvent.Audience.Unknown())
            .action(CommonContactOutboundEvent.Action.DELETE)
            .path(URI.create("addressbooks/alice/collected"))
            .uid(new ContactUid("contact-uid"))
            .revision(42L)
            .etag("\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\"")
            .build();

        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        assertThatJson(actual).isEqualTo("""
            {
              "audience": {},
              "action": "DELETE",
              "path": "addressbooks/alice/collected",
              "uid": "contact-uid",
              "revision": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\""
            }
            """);
    }

    @Test
    void shouldSerializeDomainAudience() throws Exception {
        CommonContactOutboundEvent event = ContactChangedEvent.builder()
            .audience(new Domain(org.apache.james.core.Domain.of("linagora.com")))
            .action(CommonContactOutboundEvent.Action.ADD)
            .path(URI.create("addressbooks/domain/domain-members/contact-uid.vcf"))
            .uid(new ContactUid("contact-uid"))
            .revision(42L)
            .etag("\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\"")
            .build();

        assertThatJson(new String(event.serialize(), StandardCharsets.UTF_8)).isEqualTo("""
            {
              "audience": { "domain": "linagora.com" },
              "action": "ADD",
              "path": "addressbooks/domain/domain-members/contact-uid.vcf",
              "uid": "contact-uid",
              "revision": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\""
            }
            """);
    }

    @Test
    void shouldSerializeSyncIdOfRepublishedContact() throws Exception {
        // Given a contact republished by the sync of its address book
        CommonContactOutboundEvent event = ContactChangedEvent.builder()
            .audience(new Domain(org.apache.james.core.Domain.of("linagora.com")))
            .action(CommonContactOutboundEvent.Action.ADD)
            .path(URI.create("addressbooks/domain/domain-members/contact-uid.vcf"))
            .uid(new ContactUid("contact-uid"))
            .revision(42L)
            .etag("\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\"")
            .syncId("task-id:addressbooks/domain/domain-members")
            .build();

        // When the event is serialized
        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        // Then it carries the sync ID next to the contact data
        assertThatJson(actual).isEqualTo("""
            {
              "audience": { "domain": "linagora.com" },
              "action": "ADD",
              "path": "addressbooks/domain/domain-members/contact-uid.vcf",
              "uid": "contact-uid",
              "revision": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\"",
              "syncId": "task-id:addressbooks/domain/domain-members"
            }
            """);
    }

    @Test
    void shouldSerializeSyncStartWithoutUidOrPayload() throws Exception {
        // Given the start of the sync of a domain address book
        CommonContactOutboundEvent event = new AddressBookSyncStartEvent(new Domain(org.apache.james.core.Domain.of("linagora.com")),
            URI.create("addressbooks/domain/domain-members"), "task-id:addressbooks/domain/domain-members", 42L);

        // When the event is serialized
        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        // Then it identifies the address book and the sync, without any contact data
        assertThatJson(actual).isEqualTo("""
            {
              "audience": { "domain": "linagora.com" },
              "action": "SYNC_START",
              "path": "addressbooks/domain/domain-members",
              "syncId": "task-id:addressbooks/domain/domain-members",
              "revision": 42
            }
            """);
    }

    @Test
    void shouldSerializeSyncEndWithCompletenessAndCount() throws Exception {
        // Given the end of an incomplete sync of a user address book
        CommonContactOutboundEvent event = AddressBookSyncEndEvent.builder()
            .audience(new CommonContactOutboundEvent.Audience.User(Username.of("alice@linagora.com")))
            .path(URI.create("addressbooks/alice/contacts"))
            .syncId("task-id:addressbooks/alice/contacts")
            .revision(42L)
            .complete(false)
            .count(3)
            .build();

        // When the event is serialized
        String actual = new String(event.serialize(), StandardCharsets.UTF_8);

        // Then it tells whether the sync is complete and how many contacts it sent
        assertThatJson(actual).isEqualTo("""
            {
              "audience": { "user": "alice@linagora.com" },
              "action": "SYNC_END",
              "path": "addressbooks/alice/contacts",
              "syncId": "task-id:addressbooks/alice/contacts",
              "revision": 42,
              "complete": false,
              "count": 3
            }
            """);
    }
}
