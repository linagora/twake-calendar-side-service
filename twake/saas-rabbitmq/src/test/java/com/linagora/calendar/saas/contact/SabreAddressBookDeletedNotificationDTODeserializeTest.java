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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SabreAddressBookDeletedNotificationDTODeserializeTest {
    @Test
    void shouldDeserializeAddressBookDeletionWithoutCardData() {
        SabreAddressBookDeletedNotificationDTO result = SabreAddressBookDeletedNotificationDTO.deserialize("""
            {
              "path": "addressbooks/user-id/custom",
              "owner": "principals/users/user-id",
              "connectedUser": "principals/users/admin-id"
            }
            """.getBytes(StandardCharsets.UTF_8));

        assertThat(result).isEqualTo(new SabreAddressBookDeletedNotificationDTO(
            "addressbooks/user-id/custom", "principals/users/user-id"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json",
        "{}",
        "{\"path\":\"addressbooks/user-id/custom\"}",
        "{\"owner\":\"principals/users/user-id\"}"
    })
    void shouldRejectMalformedOrIncompleteNotification(String notification) {
        assertThatThrownBy(() -> SabreAddressBookDeletedNotificationDTO.deserialize(notification.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(CommonContactNotificationDeserializeException.class);
    }
}
