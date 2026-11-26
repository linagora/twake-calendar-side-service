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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class SabreContactNotificationDTODeserializeTest {
    @Test
    void shouldDeserializeSabreContactNotification() {
        SabreContactNotificationDTO result = SabreContactNotificationDTO.deserialize("""
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "syncToken": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\"",
              "connectedUser": "principals/users/6a8406bbf00afd7e2c42b508"
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(new SabreContactNotificationDTO(
            "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
            "principals/users/6a8406bbf00afd7e2c42b508",
            "BEGIN:VCARD\nVERSION:4.0\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\nEND:VCARD\n",
            42L,
            "\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\"", Optional.empty()));
    }

    @Test
    void shouldDeserializeTheSourcePathOfAMirrorCopy() {
        // Given the notification Sabre publishes for an address book subscribed to the one holding the contact
        byte[] notification = """
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b509/1f7c9a52-0c3e-4b8e-9a3f-2d6b5e4c7a10/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "sourcePath": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b509",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "syncToken": 42,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\""
            }
            """.getBytes(StandardCharsets.UTF_8);

        // When it is deserialized
        SabreContactNotificationDTO result = SabreContactNotificationDTO.deserialize(notification);

        // Then it is identified as the copy of the contact of its source
        assertThat(result.sourcePath())
            .contains("addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf");
        assertThat(result.isMirrorCopy()).isTrue();
    }

    @Test
    void shouldThrowWhenNotificationIsMalformed() {
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize("not-json".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }

    @Test
    void shouldThrowWhenRequiredCardDataIsMissing() {
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize("""
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508"
            }
            """.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }

    @Test
    void shouldThrowWhenRequiredOwnerIsMissing() {
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize("""
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n"
            }
            """.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }

    @Test
    void shouldThrowWhenRequiredSyncTokenIsMissing() {
        // Given a Sabre notification without the sync token of the address book
        byte[] notification = """
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\""
            }
            """.getBytes(StandardCharsets.UTF_8);

        // When it is deserialized, then it is rejected rather than given a revision
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize(notification))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }

    @Test
    void shouldThrowWhenSyncTokenIsNull() {
        // Given a Sabre notification whose sync token is null
        byte[] notification = """
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "syncToken": null,
              "etag": "\\"6b1c3d2e9f0a4b5c8d7e6f5a4b3c2d1e\\""
            }
            """.getBytes(StandardCharsets.UTF_8);

        // When it is deserialized, then it is rejected rather than given the revision 0
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize(notification))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }

    @Test
    void shouldThrowWhenRequiredEtagIsMissing() {
        // Given a Sabre notification without the etag of the contact
        byte[] notification = """
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "syncToken": 42
            }
            """.getBytes(StandardCharsets.UTF_8);

        // When it is deserialized, then it is rejected
        assertThatThrownBy(() -> SabreContactNotificationDTO.deserialize(notification))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }
}
