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

import org.apache.james.core.Domain;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;

class CommonContactSyncEventSerializeTest {
    private static final Audience AUDIENCE = new Audience.Domain(Domain.of("org.example.com"));
    private static final URI BOOK = URI.create("addressbooks/domain-id/domain-members");

    @Test
    void shouldSerializeStartWithoutContactOrCompletionFields() throws Exception {
        assertThatJson(new String(CommonContactSyncEvent.start(AUDIENCE, BOOK, "task-id:book").serialize(), StandardCharsets.UTF_8))
            .isEqualTo("""
                {
                  "action": "SYNC_START",
                  "audience": { "domain": "org.example.com" },
                  "path": "addressbooks/domain-id/domain-members",
                  "syncId": "task-id:book"
                }
                """);
    }

    @Test
    void shouldSerializeEndWithCompletionAndCount() throws Exception {
        assertThatJson(new String(CommonContactSyncEvent.end(AUDIENCE, BOOK, "task-id:book", true, 42).serialize(), StandardCharsets.UTF_8))
            .isEqualTo("""
                {
                  "action": "SYNC_END",
                  "audience": { "domain": "org.example.com" },
                  "path": "addressbooks/domain-id/domain-members",
                  "syncId": "task-id:book",
                  "complete": true,
                  "count": 42
                }
                """);
    }
}
