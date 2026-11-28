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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.apache.james.core.Username;
import org.apache.james.jmap.exceptions.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.model.Aud;
import com.linagora.calendar.storage.model.Sid;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

import reactor.core.publisher.Mono;

public abstract class OIDCTokenCacheContract {

    protected static final String EMAIL = "user@example.com";
    protected static final List<Aud> AUD = ImmutableList.of(new Aud("tcalendar"));
    protected static final Instant EXPIRES_AT = Instant.now().plus(Duration.ofMinutes(1));
    protected static final String EMAIL_2 = "user2@example.com";

    // Unique per test: a logout is remembered for its session, and shared backends (Redis) outlive a test.
    protected final Sid sid = newSid();
    protected final TokenInfo tokenInfo = new TokenInfo(EMAIL, Optional.of(sid), EXPIRES_AT, AUD);
    protected final Sid sid2 = newSid();
    protected final TokenInfo tokenInfo2 = new TokenInfo(EMAIL_2, Optional.of(sid2), EXPIRES_AT, AUD);

    protected TokenInfoResolver tokenInfoResolver = mock(TokenInfoResolver.class);

    public abstract OIDCTokenCache testee();

    public abstract Optional<Username> getUsernameFromCache(Token token);

    protected Token token;
    protected Token token2;

    @BeforeEach
    void beforeEach() {
        token = newToken();
        token2 = newToken();
        mockTokenInfoResolverSuccess(token, tokenInfo);
    }

    @AfterEach
    void afterEach() {
        reset(tokenInfoResolver);
    }
    
    protected Token newToken() {
        return new Token("token-" + UUID.randomUUID());
    }

    private static Sid newSid() {
        return new Sid("sid-" + UUID.randomUUID());
    }

    @Test
    public void invalidateShouldRemoveSidFromCache() {
        testee().associatedInformation(token).block();

        assertThat(getUsernameFromCache(token)).contains(Username.of(EMAIL));

        testee().invalidate(sid).block();
        assertThat(getUsernameFromCache(token)).isEmpty();
    }

    @Test
    public void invalidateShouldRemoveTokenFromCache() {
        testee().associatedInformation(token).block();
        verify(tokenInfoResolver, times(1)).apply(token);
        testee().invalidate(sid).block();

        assertThatThrownBy(() -> testee().associatedInformation(token).block())
            .isInstanceOf(UnauthorizedException.class);
        verify(tokenInfoResolver, times(2)).apply(token);
    }

    @Test
    public void invalidateShouldDropATokenWhoseValidationIsInFlight() throws Exception {
        CountDownLatch validating = new CountDownLatch(1);
        CompletableFuture<Void> idpAnswer = new CompletableFuture<>();
        when(tokenInfoResolver.apply(token))
            .thenReturn(Mono.defer(() -> {
                validating.countDown();
                return Mono.fromFuture(idpAnswer);
            }).thenReturn(tokenInfo));

        CompletableFuture<TokenInfo> request = testee().associatedInformation(token).toFuture();
        assertThat(validating.await(10, TimeUnit.SECONDS)).isTrue();
        testee().invalidate(sid).block();
        idpAnswer.complete(null);

        assertThatThrownBy(() -> request.get(10, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .hasCauseInstanceOf(UnauthorizedException.class);
        assertThat(getUsernameFromCache(token)).isEmpty();
    }

    @Test
    public void invalidateShouldRemoveAllTokensForSid() {
        TokenInfo firstUserInfo = new TokenInfo(EMAIL, Optional.of(sid), EXPIRES_AT, AUD);
        TokenInfo secondUserInfo = new TokenInfo(EMAIL_2, Optional.of(sid), EXPIRES_AT, AUD);

        mockTokenInfoResolverSuccess(token, firstUserInfo);
        mockTokenInfoResolverSuccess(token2, secondUserInfo);

        testee().associatedInformation(token).block();
        testee().associatedInformation(token2).block();

        testee().invalidate(sid).block();

        assertThat(getUsernameFromCache(token)).isEmpty();
        assertThat(getUsernameFromCache(token2)).isEmpty();
    }

    @Test
    public void invalidateShouldNotThrowWhenSidNotCached() {
        assertThatCode(() -> testee().invalidate(new Sid(UUID.randomUUID().toString())).block())
            .doesNotThrowAnyException();
    }

    @Test
    public void invalidateShouldNotAffectOtherTokens() {
        mockTokenInfoResolverSuccess(token, tokenInfo);
        mockTokenInfoResolverSuccess(token2, tokenInfo2);
        testee().associatedInformation(token).block();
        testee().associatedInformation(token2).block();

        verify(tokenInfoResolver, times(1)).apply(token2);

        testee().invalidate(sid).block();

        testee().associatedInformation(token2).block();
        verify(tokenInfoResolver, times(1)).apply(token2);
    }

    @Test
    public void associatedUsernameShouldReturnUsername() {
        assertThat(testee().associatedInformation(token).block().email())
            .isEqualTo(EMAIL);
    }

    @Test
    public void associatedUsernameShouldThrowErrorWhenTokenCouldNotBeResolved() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverFailure(token, new RuntimeException("Token not found"));

        assertThatThrownBy(() -> testee().associatedInformation(token).block())
            .hasMessage("Token not found");
    }

    @Test
    public void associatedUsernameShouldPopulateCache() {
        testee().associatedInformation(token).block();
        assertThat(getUsernameFromCache(token))
            .contains(Username.of(EMAIL));
    }

    @Test
    public void associatedUsernameShouldNotPopulateCacheWhenCacheHit() {
        for (int i = 0; i < 5; i++) {
            testee().associatedInformation(token).block();
        }
        verify(tokenInfoResolver, times(1)).apply(token);
    }

    @Test
    public void associatedUsernameShouldThrowWhenInvalidateRelatedSid() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverSuccess(token, tokenInfo);
        testee().associatedInformation(token).block();
        testee().invalidate(sid).block();

        mockTokenInfoResolverFailure(token, new RuntimeException("Token expired"));

        assertThatThrownBy(() -> testee().associatedInformation(token).block())
            .hasMessage("Token expired");
    }

    @Test
    public void associatedUsernameShouldCachedWhenAbsentSidInTokenInfo() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverSuccess(token, new TokenInfo(EMAIL, Optional.empty(), EXPIRES_AT, AUD));

        for (int i = 0; i < 5; i++) {
            testee().associatedInformation(token).block();
        }
        verify(tokenInfoResolver, times(1)).apply(token);
        assertThat(getUsernameFromCache(token)).contains(Username.of(EMAIL));
    }

    public void mockTokenInfoResolverSuccess(Token token, TokenInfo expected) {
        when(tokenInfoResolver.apply(token))
            .thenReturn(Mono.just(expected));
    }

    public void mockTokenInfoResolverFailure(Token token, Exception expected) {
        when(tokenInfoResolver.apply(token))
            .thenReturn(Mono.error(expected));
    }
}
