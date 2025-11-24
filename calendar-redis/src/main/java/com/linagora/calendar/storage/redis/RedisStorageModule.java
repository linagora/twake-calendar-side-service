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

import java.io.FileNotFoundException;
import java.util.List;
import java.util.function.Function;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.backends.redis.ClusterRedisConfiguration;
import org.apache.james.backends.redis.MasterReplicaRedisConfiguration;
import org.apache.james.backends.redis.RedisClientFactory;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.backends.redis.SentinelRedisConfiguration;
import org.apache.james.backends.redis.StandaloneRedisConfiguration;
import org.apache.james.utils.PropertiesProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.linagora.calendar.storage.OIDCTokenCache;
import com.linagora.calendar.storage.TokenInfoResolver;
import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;

import io.lettuce.core.AbstractRedisClient;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.cluster.ClusterClientOptions;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.masterreplica.MasterReplica;
import scala.jdk.javaapi.CollectionConverters;

public class RedisStorageModule extends AbstractModule {
    public static final Logger LOGGER = LoggerFactory.getLogger(RedisStorageModule.class);

    @Override
    protected void configure() {
        bind(OIDCTokenCache.class).to(RedisOIDCTokenCache.class)
            .in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    public RedisOIDCTokenCache provideRedisOIDCTokenCache(RedisClientFactory redisClientFactory,
                                                          RedisConfiguration redisConfiguration,
                                                          OIDCTokenCacheConfiguration oidcTokenCacheConfiguration,
                                                          TokenInfoResolver tokenInfoResolver) {

        AbstractRedisClient rawClient = redisClientFactory.rawRedisClient();

        Function<AbstractRedisClient, RedisClient> toRedisClient = client -> {
            RedisClient redisClient = (RedisClient) rawClient;
            redisClient.setOptions(ClientOptions.builder()
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
            return redisClient;
        };

        RedisTokenCacheCommands redisReactiveCommands = switch (redisConfiguration) {
            case StandaloneRedisConfiguration ignored ->
                RedisTokenCacheCommands.of(toRedisClient.apply(rawClient).connect(StringCodec.UTF8).reactive());

            case ClusterRedisConfiguration ignored -> {
                RedisClusterClient client = (RedisClusterClient) rawClient;
                client.setOptions(ClusterClientOptions.builder()
                    .timeoutOptions(TimeoutOptions.enabled())
                    .build());
                yield RedisTokenCacheCommands.of(client.connect(StringCodec.UTF8).reactive());
            }

            case SentinelRedisConfiguration sentinelConf ->
                RedisTokenCacheCommands.of(MasterReplica.connect(toRedisClient.apply(rawClient), StringCodec.UTF8, sentinelConf.redisURI()).reactive());

            case MasterReplicaRedisConfiguration replicaConf -> {
                List<RedisURI> uris = CollectionConverters.asJava(replicaConf.redisURI());
                yield RedisTokenCacheCommands.of(MasterReplica.connect(toRedisClient.apply(rawClient), StringCodec.UTF8, uris).reactive());
            }
            default ->
                throw new RuntimeException("Unknown redis configuration type: " + redisConfiguration.getClass().getName());
        };

        return new RedisOIDCTokenCache(tokenInfoResolver, oidcTokenCacheConfiguration, redisReactiveCommands);
    }

    @Provides
    @Singleton
    public RedisConfiguration redisConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return RedisConfiguration.from(propertiesProvider.getConfiguration("redis"));
    }
}
