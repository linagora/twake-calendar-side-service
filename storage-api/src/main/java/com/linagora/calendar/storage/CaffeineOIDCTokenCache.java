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

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Ticker;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;
import com.linagora.calendar.storage.model.Sid;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CaffeineOIDCTokenCache implements OIDCTokenCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineOIDCTokenCache.class);
    private static final long DEFAULT_TOKEN_CACHE_MAX_SIZE = 10_000;

    private final AsyncLoadingCache<Token, TokenInfo> cacheToken;
    private final SetMultimap<Sid, Token> sidToTokens = Multimaps.synchronizedSetMultimap(HashMultimap.create());
    // Sessions logged out within the cache expiration. invalidate(sid) evicts the tokens indexed at that time; a token
    // indexed later (its validation was in flight, or the eviction listener unindexed a reloaded token) is caught here.
    private final Cache<Sid, Boolean> loggedOutSids;

    @Inject
    public CaffeineOIDCTokenCache(TokenInfoResolver tokenInfoResolver, OIDCTokenCacheConfiguration configuration) {
        this(tokenInfoResolver, configuration, Ticker.systemTicker(), ForkJoinPool.commonPool());
    }

    @VisibleForTesting
    CaffeineOIDCTokenCache(TokenInfoResolver tokenInfoResolver, OIDCTokenCacheConfiguration configuration, Ticker ticker, Executor executor) {
        AsyncCacheLoader<Token, TokenInfo> cacheLoader = (token, loadExecutor) -> tokenInfoResolver.apply(token)
            .map(tokenInfo -> {
                tokenInfo.sid().ifPresentOrElse(sidValue -> sidToTokens.put(sidValue, token),
                    () -> LOGGER.warn("OIDC token of user {} does not have a sid, this will break backchannel logout. Please review OIDC configuration.", tokenInfo.email()));
                return tokenInfo;
            })
            .subscribeOn(Schedulers.fromExecutor(loadExecutor))
            .toFuture();

        cacheToken = Caffeine.newBuilder()
            .ticker(ticker)
            .executor(executor)
            .expireAfterWrite(configuration.expiration())
            .maximumSize(configuration.tokenCacheMaxSize().orElse(DEFAULT_TOKEN_CACHE_MAX_SIZE))
            .removalListener((Token token, TokenInfo tokenInfo, RemovalCause cause) -> {
                if (cause.wasEvicted() && tokenInfo != null) {
                    tokenInfo.sid().ifPresent(sid -> sidToTokens.remove(sid, token));
                }
            })
            .buildAsync(cacheLoader);

        loggedOutSids = Caffeine.newBuilder()
            .ticker(ticker)
            .executor(executor)
            .expireAfterWrite(configuration.expiration())
            .maximumSize(configuration.tokenCacheMaxSize().orElse(DEFAULT_TOKEN_CACHE_MAX_SIZE))
            .build();
    }

    @Override
    public Mono<Void> invalidate(Sid sid) {
        // The mark is written before the index is read, and a token is indexed before its mark is checked:
        // a token missed by this snapshot is refused by associatedInformation.
        return Mono.fromRunnable(() -> loggedOutSids.put(sid, true))
            .then(Mono.fromCallable(() -> {
                synchronized (sidToTokens) {
                    return List.copyOf(sidToTokens.get(sid));
                }
            }))
            .flatMap(tokenList -> Mono.fromRunnable(() -> cacheToken.synchronous().invalidateAll(tokenList)).subscribeOn(Schedulers.boundedElastic()))
            .then(Mono.fromRunnable(() -> sidToTokens.removeAll(sid)))
            .then();
    }

    @Override
    public Mono<TokenInfo> associatedInformation(Token token) {
        return Mono.fromFuture(cacheToken.get(token))
            .<TokenInfo>handle((tokenInfo, sink) -> {
                if (tokenInfo.sid().map(sid -> loggedOutSids.getIfPresent(sid) != null).orElse(false)) {
                    tokenInfo.sid().ifPresent(sid -> sidToTokens.remove(sid, token));
                    cacheToken.synchronous().invalidate(token);
                    sink.error(new UnauthorizedException("The OIDC session of this token was logged out"));
                    return;
                }
                sink.next(tokenInfo);
            });
    }

    @VisibleForTesting
    Optional<Username> getUsernameFromCache(Token token) {
        return Optional.ofNullable(cacheToken.synchronous().getIfPresent(token))
            .map(TokenInfo::email)
            .map(Username::of);
    }
}
