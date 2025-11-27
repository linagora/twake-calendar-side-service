/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package com.linagora.calendar.storage.redis;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.james.backends.redis.RedisClientFactory;
import org.apache.james.backends.redis.RedisConfiguration;
import org.apache.james.core.Username;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.common.hash.Hashing;
import com.linagora.calendar.storage.OIDCTokenCache;
import com.linagora.calendar.storage.OIDCTokenCacheContract;
import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

public abstract class RedisOIDCTokenCacheContract extends OIDCTokenCacheContract {

    public abstract void pauseRedis();

    private RedisOIDCTokenCache redisOIDCTokenCache;

    @BeforeEach
    void setUp() {
        RedisConfiguration redisConfiguration = redisConfiguration();
        RedisClientFactory redisClientFactory = new RedisClientFactory(FileSystemImpl.forTesting(), redisConfiguration);
        redisOIDCTokenCache = new RedisOIDCModule().provideRedisOIDCTokenCache(redisClientFactory, redisConfiguration, OIDCTokenCacheConfiguration.DEFAULT, tokenInfoResolver);
    }

    @Override
    public OIDCTokenCache testee() {
        return redisOIDCTokenCache;
    }

    public abstract RedisConfiguration redisConfiguration();

    @Override
    public Optional<Username> getUsernameFromCache(Token token) {
        return redisOIDCTokenCache.getTokenInfoFromCache("tcal_oidc_hash_" + Hashing.sha512().hashString(token.value(), StandardCharsets.UTF_8))
            .map(TokenInfo::email)
            .map(Username::of)
            .blockOptional();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void associatedUsernameShouldStillReturnValueWhenRedisIsDown() {
        mockTokenInfoResolverSuccess(token, TOKEN_INFO);

        pauseRedis();

        assertThat(testee().associatedInformation(token).block())
            .isEqualTo(TOKEN_INFO);
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void invalidateShouldSwallowRedisError() {
        pauseRedis();
        assertThatCode(() -> testee().invalidate(SID).block())
            .doesNotThrowAnyException();
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @Test
    public void shouldFallbackGracefullyWhenRedisIsDown() {
        mockTokenInfoResolverSuccess(token, TOKEN_INFO);
        testee().associatedInformation(token).block();

        pauseRedis();

        assertThatCode(() -> testee().invalidate(SID).block())
            .doesNotThrowAnyException();

        assertThat(testee().associatedInformation(token).block())
            .isEqualTo(TOKEN_INFO);
    }
}
