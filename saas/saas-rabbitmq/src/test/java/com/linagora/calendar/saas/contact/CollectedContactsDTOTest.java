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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CollectedContactsDTOTest {
    @Test
    void shouldDeserializeCollectedContacts() {
        CollectedContactsDTO result = CollectedContactsDTO.deserialize("""
            {
              "userEmail": "alice@linagora.com",
              "collectedContacts": [
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "contact-uid",
                  "name": {
                    "@type": "Name",
                    "full": "Bob"
                  }
                }
              ],
              "ignored": true
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.userEmail()).isEqualTo("alice@linagora.com");
        assertThat(result.collectedContacts()).hasSize(1);
        assertThatJson(result.collectedContacts().getFirst()).isEqualTo("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "name": {
                "@type": "Name",
                "full": "Bob"
              }
            }
            """);
    }

    @Test
    void shouldDeserializeMultipleCollectedContacts() {
        CollectedContactsDTO result = CollectedContactsDTO.deserialize("""
            {
              "userEmail": "alice@linagora.com",
              "collectedContacts": [
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "bob-contact",
                  "name": {
                    "@type": "Name",
                    "full": "Bob"
                  }
                },
                {
                  "@type": "Card",
                  "version": "2.0",
                  "uid": "charlie-contact",
                  "name": {
                    "@type": "Name",
                    "full": "Charlie"
                  }
                }
              ]
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(result.collectedContacts()).hasSize(2);
        assertThatJson(result.collectedContacts()).isEqualTo("""
            [
              {
                "@type": "Card",
                "version": "2.0",
                "uid": "bob-contact",
                "name": {
                  "@type": "Name",
                  "full": "Bob"
                }
              },
              {
                "@type": "Card",
                "version": "2.0",
                "uid": "charlie-contact",
                "name": {
                  "@type": "Name",
                  "full": "Charlie"
                }
              }
            ]
            """);
    }

    @Test
    void shouldThrowWhenMessageIsMalformed() {
        assertThatThrownBy(() -> CollectedContactsDTO.deserialize("not-json".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CollectedContactsDeserializeException.class);
    }

    @Test
    void shouldThrowWhenUserEmailIsMissing() {
        assertThatThrownBy(() -> CollectedContactsDTO.deserialize("""
            {
              "collectedContacts": []
            }
            """.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CollectedContactsDeserializeException.class);
    }

    @Test
    void shouldThrowWhenCollectedContactsIsMissing() {
        assertThatThrownBy(() -> CollectedContactsDTO.deserialize("""
            {
              "userEmail": "alice@linagora.com"
            }
            """.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CollectedContactsDeserializeException.class);
    }
}
