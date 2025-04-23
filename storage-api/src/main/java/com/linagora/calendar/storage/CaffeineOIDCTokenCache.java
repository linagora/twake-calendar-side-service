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

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;
import com.linagora.calendar.storage.model.Sid;
import com.linagora.calendar.storage.model.Token;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CaffeineOIDCTokenCache implements OIDCTokenCache {
    record CacheEntry(Username username, Optional<Sid> maybeSid) {}

    private static final Logger LOGGER = LoggerFactory.getLogger(CaffeineOIDCTokenCache.class);
    private static final long DEFAULT_TOKEN_CACHE_MAX_SIZE = 10_000;

    private final AsyncLoadingCache<Token, CacheEntry> cacheToken;
    private final SetMultimap<Sid, Token> sidToTokens = Multimaps.synchronizedSetMultimap(HashMultimap.create());

    @Inject
    public CaffeineOIDCTokenCache(TokenInfoResolver tokenInfoResolver, OIDCTokenCacheConfiguration configuration) {
        AsyncCacheLoader<Token, CacheEntry> cacheLoader = (token, executor) -> tokenInfoResolver.apply(token)
            .map(tokenInfo -> {
                tokenInfo.sid().ifPresentOrElse(sidValue -> sidToTokens.put(sidValue, token),
                    () -> LOGGER.warn("Token {} of user {} does not have a sid, this will break backchannel logout. Please review OIDC configuration.", token.value(), tokenInfo.email()));
                return new CacheEntry(Username.of(tokenInfo.email()), tokenInfo.sid());
            })
            .subscribeOn(Schedulers.fromExecutor(executor))
            .toFuture();

        cacheToken = Caffeine.newBuilder()
            .expireAfterWrite(configuration.expiration())
            .maximumSize(configuration.tokenCacheMaxSize().orElse(DEFAULT_TOKEN_CACHE_MAX_SIZE))
            .removalListener((Token token, CacheEntry cacheEntry, RemovalCause cause) -> {
                if (cause.wasEvicted() && cacheEntry != null) {
                    cacheEntry.maybeSid().ifPresent(sid -> sidToTokens.remove(sid, token));
                }
            })
            .buildAsync(cacheLoader);
    }

    @Override
    public Mono<Void> invalidate(Sid sid) {
        return Flux.fromIterable(sidToTokens.get(sid))
            .collectList()
            .flatMap(tokenList -> Mono.fromRunnable(() -> cacheToken.synchronous().invalidateAll(tokenList)))
            .then(Mono.fromRunnable(() -> sidToTokens.removeAll(sid)))
            .then();
    }

    @Override
    public Mono<Username> associatedUsername(Token token) {
        return Mono.fromFuture(cacheToken.get(token))
            .map(CacheEntry::username);
    }

    @VisibleForTesting
    Optional<Username> getUsernameFromCache(Token token) {
        return Optional.ofNullable(cacheToken.synchronous().getIfPresent(token))
            .map(CacheEntry::username);
    }
}
