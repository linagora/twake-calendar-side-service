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

import java.time.Duration;

import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.RedisSentinelExtension;
import org.apache.james.backends.redis.SentinelRedisConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.lettuce.core.RedisURI;

public class RedisSentinelOIDCTokenCacheTest extends RedisOIDCTokenCacheContract {

    @RegisterExtension
    static RedisSentinelExtension redisSentinelExtension = new RedisSentinelExtension();

    private RedisConfiguration redisConfiguration;

    @AfterEach
    void tearDown() {
        redisSentinelExtension.getRedisSentinelCluster().redisMasterReplicaContainerList().unPauseMasterNode();
    }

    @Override
    public RedisConfiguration redisConfiguration() {
        if (redisConfiguration == null) {
            SentinelRedisConfiguration sentinelRedisConfiguration = redisSentinelExtension.getRedisSentinelCluster().redisSentinelContainerList().getRedisConfiguration();
            RedisURI redisURI = sentinelRedisConfiguration.redisURI();
            redisURI.setTimeout(Duration.ofSeconds(3));

            redisConfiguration = sentinelRedisConfiguration;
        }
        return redisConfiguration;
    }

    @Override
    public void pauseRedis() {
        redisSentinelExtension.getRedisSentinelCluster().redisMasterReplicaContainerList().pauseMasterNode();
    }
}
