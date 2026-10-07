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

package com.linagora.calendar.twakespace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

class SpaceEventTest {
    @Test
    void deserializeShouldReadASpaceCreatedEvent() {
        String body = """
            {"organizationId": "org", "id": "space-1", "name": "Marketing", "members": [
              {"uuid": "1", "username": "alice", "email": "alice@space.tld", "firstName": "Alice", "lastName": "Doe", "role": "admin"}],
             "groups": [], "actor": "alice@space.tld", "timestamp": "2026-10-06T10:00:00.000Z"}""";

        assertThat(SpaceEvent.deserialize(body.getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(new SpaceEvent(new OrganizationId("org"), null, new SpaceId("space-1"), "Marketing",
                List.of(new SpaceEvent.Member("alice@space.tld", "admin"))));
    }

    @Test
    void deserializeShouldFailOnABlankId() {
        String body = """
            {"organizationId": "org", "id": " ", "name": "Marketing"}""";

        assertThatThrownBy(() -> SpaceEvent.deserialize(body.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(UnprocessableSpaceEventException.class);
    }

    @Test
    void deserializeShouldDefaultToNoMember() {
        String body = """
            {"organizationId": "org", "id": "space-1", "actor": "alice@space.tld", "timestamp": "2026-10-06T10:00:00.000Z"}""";

        assertThat(SpaceEvent.deserialize(body.getBytes(StandardCharsets.UTF_8)).members()).isEmpty();
    }

    @Test
    void deserializeShouldFailWithoutId() {
        String body = """
            {"organizationId": "org", "name": "Marketing"}""";

        assertThatThrownBy(() -> SpaceEvent.deserialize(body.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(UnprocessableSpaceEventException.class);
    }

    @Test
    void deserializeShouldFailOnAMemberWithoutEmail() {
        String body = """
            {"id": "space-1", "members": [{"username": "alice", "role": "admin"}]}""";

        assertThatThrownBy(() -> SpaceEvent.deserialize(body.getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(UnprocessableSpaceEventException.class);
    }
}
