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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

public class CaffeineOIDCTokenCacheTest extends OIDCTokenCacheContract {

    private CaffeineOIDCTokenCache testee;

    @BeforeEach
    void setUp() {
        testee = new CaffeineOIDCTokenCache(tokenInfoResolver, OIDCTokenCacheConfiguration.DEFAULT);
    }

    @Override
    public OIDCTokenCache testee() {
        return testee;
    }

    @Override
    public Optional<Username> getUsernameFromCache(Token token) {
        return testee.getUsernameFromCache(token);
    }

    @Test
    void invalidateShouldDropATokenReloadedBeforeItsEvictionWasNotified() {
        AtomicLong nanos = new AtomicLong();
        Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        CaffeineOIDCTokenCache cache = new CaffeineOIDCTokenCache(tokenInfoResolver, OIDCTokenCacheConfiguration.DEFAULT, nanos::get, tasks::add);

        CompletableFuture<TokenInfo> firstRequest = cache.associatedInformation(token).toFuture();
        runAll(tasks);
        assertThat(firstRequest.join()).isEqualTo(TOKEN_INFO);

        // The token expires, and a request reloads it before the removal listener of the expired entry runs:
        // the listener then unindexes the reloaded token.
        nanos.addAndGet(OIDCTokenCacheConfiguration.DEFAULT.expiration().plus(Duration.ofSeconds(1)).toNanos());
        CompletableFuture<TokenInfo> reload = cache.associatedInformation(token).toFuture();
        runAll(tasks);
        assertThat(reload.join()).isEqualTo(TOKEN_INFO);
        verify(tokenInfoResolver, times(2)).apply(token);

        cache.invalidate(SID).block();
        CompletableFuture<TokenInfo> afterLogout = cache.associatedInformation(token).toFuture();
        runAll(tasks);

        assertThat(afterLogout).isCompletedExceptionally();
        assertThat(cache.getUsernameFromCache(token)).isEmpty();
    }

    private static void runAll(Queue<Runnable> tasks) {
        Runnable task;
        while ((task = tasks.poll()) != null) {
            task.run();
        }
    }
}
