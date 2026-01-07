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

package com.linagora.calendar.restapi.routes;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.restapi.routes.WebSocketNotificationListener.ImportWebSocketMessage;
import com.linagora.calendar.restapi.routes.WebSocketNotificationListener.ImportWebSocketMessage.ImportResultMessage;
import com.linagora.calendar.storage.model.ImportId;

public class ImportWebSocketMessageSerializeTest {
    @Test
    void serializeShouldExposeExpectedStructure() throws Exception {
        URI targetUri = URI.create("/calendars/user/default");
        ImportId importId = new ImportId("import-123");

        ImportWebSocketMessage message = new ImportWebSocketMessage(targetUri,
            Map.of(importId, new ImportResultMessage("completed", 45, 0)));

        assertThatJson(message.serialize())
            .isEqualTo("""
                {
                  "/calendars/user/default" : {
                    "imports" : {
                      "import-123" : {
                        "status" : "completed",
                        "succeedCount" : 45,
                        "failedCount" : 0
                      }
                    }
                  }
                }""");
    }
}
