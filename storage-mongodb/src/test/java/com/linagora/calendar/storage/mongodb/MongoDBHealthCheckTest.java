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

package com.linagora.calendar.storage.mongodb;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;
import org.apache.james.core.healthcheck.ResultStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import reactor.core.publisher.Mono;

public class MongoDBHealthCheckTest {

    @RegisterExtension
    static DockerMongoDBExtension mongoDBExtension = new DockerMongoDBExtension();

    private HealthCheck testee;

    @BeforeEach
    void setup() {
        testee = new MongoDBHealthCheck(mongoDBExtension.getDb());
    }

    @Test
    void shouldBeHealthy() {
        Result result = Mono.from(testee.check()).block();
        assertThat(result.getStatus()).isEqualTo(ResultStatus.HEALTHY);
    }

    @Test
    void shouldBeUnhealthyWhenPaused() {
        try {
            mongoDBExtension.pause();
            Result result = Mono.from(testee.check()).block();
            assertThat(result.getStatus()).isEqualTo(ResultStatus.UNHEALTHY);
        } finally {
            mongoDBExtension.unpause();
        }
    }
}
