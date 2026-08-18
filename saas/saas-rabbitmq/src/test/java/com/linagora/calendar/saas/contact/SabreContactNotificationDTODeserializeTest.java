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

import org.junit.jupiter.api.Test;

class SabreContactNotificationDTODeserializeTest {
    @Test
    void shouldDeserializeSabreContactNotification() {
        SabreContactNotificationDTO result = SabreContactNotificationDTO.deserialize("""
            {
              "path": "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
              "owner": "principals/users/6a8406bbf00afd7e2c42b508",
              "carddata": "BEGIN:VCARD\\nVERSION:4.0\\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\\nEND:VCARD\\n",
              "connectedUser": "principals/users/6a8406bbf00afd7e2c42b508"
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(new SabreContactNotificationDTO(
            "addressbooks/6a8406bbf00afd7e2c42b508/collected/7d8a3823-29f0-411c-a1cf-1b1bae658438.vcf",
            "principals/users/6a8406bbf00afd7e2c42b508",
            "BEGIN:VCARD\nVERSION:4.0\nUID:7d8a3823-29f0-411c-a1cf-1b1bae658438\nEND:VCARD\n"));
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
}
