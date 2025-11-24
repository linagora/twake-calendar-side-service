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

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;

public class DockerRedisExtension implements BeforeAllCallback, AfterAllCallback, AfterEachCallback {

    public static final RedisContainer redisContainer = new RedisContainer(DockerImageName.parse("redis:6.2.6"));
    public static final Duration redisTimeout = Duration.ofSeconds(3);

    private StatefulRedisConnection redisClientReactive;

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        redisContainer.start();

        RedisClient redisClient = RedisClient.create(redisURI());
        redisClient.setOptions(ClientOptions.builder()
            .timeoutOptions(TimeoutOptions.enabled())
            .build());

        redisClientReactive = redisClient.connect();
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        redisContainer.stop();
        redisClientReactive.close();
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws IOException, InterruptedException {
        flushAll();
    }

    public void pause() {
        if (!isPaused()) {
            redisContainer.getDockerClient().pauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    public void unPause() {
        if (isPaused()) {
            redisContainer.getDockerClient().unpauseContainerCmd(redisContainer.getContainerId()).exec();
        }
    }

    private boolean isPaused() {
        return redisContainer.getDockerClient().inspectContainerCmd(redisContainer.getContainerId())
            .exec()
            .getState()
            .getPaused();
    }

    public RedisReactiveCommands<String, String> redisReactiveCommands() {
        return redisClientReactive.reactive();
    }

    public void flushAll() throws IOException, InterruptedException {
        redisContainer.execInContainer("redis-cli", "flushall");
    }

    public RedisURI redisURI() {
        return RedisURI.builder()
            .withHost(redisContainer.getRedisHost())
            .withPort(redisContainer.getRedisPort())
            .withTimeout(redisTimeout)
            .build();
    }
}
