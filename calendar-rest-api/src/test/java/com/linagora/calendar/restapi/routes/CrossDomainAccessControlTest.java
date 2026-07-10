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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.restapi.RestApiConfiguration;

class CrossDomainAccessControlTest {
    private static final String ADMIN = "admin@linagora.com";
    private static final Domain DOMAIN_A = Domain.of("domain-a.com");
    private static final Domain DOMAIN_B = Domain.of("domain-b.com");

    private static MailboxSession sessionOf(Username username) {
        return new MailboxSession(MailboxSession.SessionId.of(1L), username, Optional.of(username),
            List.of(), '.', MailboxSession.SessionType.User);
    }

    private CrossDomainAccessControl testee;

    @BeforeEach
    void setUp() {
        testee = new CrossDomainAccessControl(RestApiConfiguration.builder()
            .adminUsername(Optional.of(ADMIN))
            .adminPassword(Optional.of("secret"))
            .build());
    }

    @Test
    void shouldAllowUserAccessingItsOwnDomain() {
        MailboxSession session = sessionOf(Username.fromLocalPartWithDomain("alice", DOMAIN_A.asString()));

        assertThat(testee.denies(session, DOMAIN_A)).isFalse();
    }

    @Test
    void shouldDenyUserAccessingAnotherDomain() {
        MailboxSession session = sessionOf(Username.fromLocalPartWithDomain("alice", DOMAIN_A.asString()));

        assertThat(testee.denies(session, DOMAIN_B)).isTrue();
    }

    @Test
    void shouldAllowAdminAccessingItsOwnDomain() {
        MailboxSession session = sessionOf(Username.of(ADMIN));

        assertThat(testee.denies(session, Domain.of("linagora.com"))).isFalse();
    }

    @Test
    void shouldAllowAdminAccessingAnotherDomain() {
        MailboxSession session = sessionOf(Username.of(ADMIN));

        assertThat(testee.denies(session, DOMAIN_B)).isFalse();
    }

    @Test
    void shouldAllowAdminWhenDomainPartCasingDiffers() {
        MailboxSession session = sessionOf(Username.of("admin@LINAGORA.COM"));

        assertThat(testee.denies(session, DOMAIN_B)).isFalse();
    }

    @Test
    void shouldDenyUserWhoseLocalPartMatchesAdminInAnotherDomain() {
        MailboxSession session = sessionOf(Username.of("admin@domain-a.com"));

        assertThat(testee.denies(session, DOMAIN_B)).isTrue();
    }

    @Test
    void shouldDenyUserWithoutDomainPart() {
        MailboxSession session = sessionOf(Username.of("alice"));

        assertThat(testee.denies(session, DOMAIN_B)).isTrue();
    }
}
