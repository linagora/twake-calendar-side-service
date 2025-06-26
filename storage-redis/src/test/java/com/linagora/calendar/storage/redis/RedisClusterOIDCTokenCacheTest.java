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
package com.linagora.calendar.storage.redis;

import static org.awaitility.Awaitility.await;

import java.time.Duration;

import org.apache.james.backends.redis.RedisClusterExtension;
import org.apache.james.backends.redis.RedisConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;

public class RedisClusterOIDCTokenCacheTest extends RedisOIDCTokenCacheContract {

    @RegisterExtension
    static RedisClusterExtension redisClusterExtension = new RedisClusterExtension();

    private static RedisClusterExtension.RedisClusterContainer redisClusterContainer;
    private RedisConfiguration redisConfiguration;

    @BeforeAll
    static void setUp(RedisClusterExtension.RedisClusterContainer container) {
        redisClusterContainer = container;
        container.forEach(genericContainer ->  waitUntilClusterReady(genericContainer, Duration.ofSeconds(10)));
    }

    private static void waitUntilClusterReady(GenericContainer<?> container, Duration timeout) {
        await().atMost(timeout)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(() -> {
                Container.ExecResult result = container.execInContainer("redis-cli", "cluster", "info");
                return result.getExitCode() == 0 && result.getStdout().contains("cluster_state:ok");
            });
    }

    @AfterEach
    void tearDown() {
        redisClusterContainer.unPauseOne();
    }

    @Override
    public RedisConfiguration redisConfiguration() {
        if (redisConfiguration == null) {
            redisConfiguration = redisClusterContainer.getRedisConfiguration();
        }
        return redisConfiguration;
    }

    @Override
    public void pauseRedis() {
        redisClusterContainer.unPauseOne();
    }
}
