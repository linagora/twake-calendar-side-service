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

import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.Username;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.model.Sid;
import com.linagora.calendar.storage.model.Token;
import com.linagora.calendar.storage.model.TokenInfo;

import reactor.core.publisher.Mono;

public abstract class OIDCTokenCacheContract {

    static final String EMAIL = "user@example.com";
    static final String SID_STRING = "sid-1";
    static final Token TOKEN = new Token("token-1");
    static final Sid SID = new Sid(SID_STRING);
    static final TokenInfo TOKEN_INFO = new TokenInfo(EMAIL, Optional.of(SID));

    static final String EMAIL_2 = "user2@example.com";
    static final String SID_STRING_2 = "sid-2";
    static final Token TOKEN_2 = new Token("token-2");
    static final Sid SID_2 = new Sid(SID_STRING_2);
    static final TokenInfo TOKEN_INFO_2 = new TokenInfo(EMAIL_2, Optional.of(SID_2));

    protected TokenInfoResolver tokenInfoResolver = mock(TokenInfoResolver.class);

    public abstract OIDCTokenCache testee();

    public abstract Optional<Username> getUsernameFromCache(Token token);

    @BeforeEach
    void beforeEach() {
        mockTokenInfoResolverSuccess(TOKEN, TOKEN_INFO);
    }

    @AfterEach
    void afterEach() {
        reset(tokenInfoResolver);
    }

    @Test
    public void invalidateShouldRemoveSidFromCache() {
        testee().associatedUsername(TOKEN).block();

        assertThat(getUsernameFromCache(TOKEN)).contains(Username.of(EMAIL));

        testee().invalidate(SID).block();
        assertThat(getUsernameFromCache(TOKEN)).isEmpty();
    }

    @Test
    public void invalidateShouldRemoveTokenFromCache() {
        testee().associatedUsername(TOKEN).block();
        verify(tokenInfoResolver, times(1)).apply(TOKEN);
        testee().invalidate(SID).block();

        testee().associatedUsername(TOKEN).block();
        verify(tokenInfoResolver, times(2)).apply(TOKEN);
    }

    @Test
    public void invalidateShouldRemoveAllTokensForSid() {
        TokenInfo tokenInfo1 = new TokenInfo(EMAIL, Optional.of(SID));
        TokenInfo tokenInfo2 = new TokenInfo(EMAIL_2, Optional.of(SID));

        mockTokenInfoResolverSuccess(TOKEN, tokenInfo1);
        mockTokenInfoResolverSuccess(TOKEN_2, tokenInfo2);

        testee().associatedUsername(TOKEN).block();
        testee().associatedUsername(TOKEN_2).block();

        testee().invalidate(SID).block();

        assertThat(getUsernameFromCache(TOKEN)).isEmpty();
        assertThat(getUsernameFromCache(TOKEN_2)).isEmpty();
    }

    @Test
    public void invalidateShouldNotThrowWhenSidNotCached() {
        assertThatCode(() -> testee().invalidate(new Sid(UUID.randomUUID().toString())).block())
            .doesNotThrowAnyException();
    }

    @Test
    public void invalidateShouldNotAffectOtherTokens() {
        mockTokenInfoResolverSuccess(TOKEN, TOKEN_INFO);
        mockTokenInfoResolverSuccess(TOKEN_2, TOKEN_INFO_2);
        testee().associatedUsername(TOKEN).block();
        testee().associatedUsername(TOKEN_2).block();

        verify(tokenInfoResolver, times(1)).apply(TOKEN_2);

        testee().invalidate(SID).block();

        testee().associatedUsername(TOKEN_2).block();
        verify(tokenInfoResolver, times(1)).apply(TOKEN_2);
    }

    @Test
    public void associatedUsernameShouldReturnUsername() {
        assertThat(testee().associatedUsername(TOKEN).block())
            .isEqualTo(Username.of(EMAIL));
    }

    @Test
    public void associatedUsernameShouldThrowErrorWhenTokenCouldNotBeResolved() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverFailure(token, new RuntimeException("Token not found"));

        assertThatThrownBy(() -> testee().associatedUsername(token).block())
            .hasMessage("Token not found");
    }

    @Test
    public void associatedUsernameShouldPopulateCache() {
        testee().associatedUsername(TOKEN).block();
        assertThat(getUsernameFromCache(TOKEN))
            .contains(Username.of(EMAIL));
    }

    @Test
    public void associatedUsernameShouldNotPopulateCacheWhenCacheHit() {
        for (int i = 0; i < 5; i++) {
            testee().associatedUsername(TOKEN).block();
        }
        verify(tokenInfoResolver, times(1)).apply(TOKEN);
    }

    @Test
    public void associatedUsernameShouldThrowWhenInvalidateRelatedSid() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverSuccess(token, TOKEN_INFO);
        testee().associatedUsername(token).block();
        testee().invalidate(SID).block();

        mockTokenInfoResolverFailure(token, new RuntimeException("Token expired"));

        assertThatThrownBy(() -> testee().associatedUsername(token).block())
            .hasMessage("Token expired");
    }

    @Test
    public void associatedUsernameShouldCachedWhenAbsentSidInTokenInfo() {
        Token token = new Token("token-" + UUID.randomUUID());
        mockTokenInfoResolverSuccess(token, new TokenInfo(EMAIL, Optional.empty()));

        for (int i = 0; i < 5; i++) {
            testee().associatedUsername(token).block();
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
