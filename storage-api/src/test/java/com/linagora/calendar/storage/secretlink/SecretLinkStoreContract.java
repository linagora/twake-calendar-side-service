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

package com.linagora.calendar.storage.secretlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.function.Supplier;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSId;

public interface SecretLinkStoreContract {

    CalendarURL CALENDAR_URL = CalendarURL.from(new OpenPaaSId(UUID.randomUUID().toString()));
    CalendarURL CALENDAR_URL_2 = CalendarURL.from(new OpenPaaSId(UUID.randomUUID().toString()));
    Username USERNAME = Username.of("username@domain.tld");
    Username USERNAME_2 = Username.of("username2@domain.tld");
    MailboxSession SESSION = MailboxSessionUtil.create(USERNAME);
    MailboxSession SESSION_2 = MailboxSessionUtil.create(USERNAME_2);

    SecretLinkStore testee();

    void setPermissionChecker(boolean value);

    default void setupBeforeEach() {
        setPermissionChecker(true);
    }

    @Test
    default void generateSecretLinkShouldCreateNewTokenEachTime() {
        Supplier<SecretLinkToken> secretLinkTokenSupplier = () -> testee().generateSecretLink(CALENDAR_URL, SESSION).block();

        assertThat(secretLinkTokenSupplier.get()).
            isNotEqualTo(secretLinkTokenSupplier.get());
    }

    @Test
    default void getSecretLinkShouldCreateTokenIfNotExists() {
        CalendarURL newCalendarUrl = CalendarURL.from(new OpenPaaSId(UUID.randomUUID().toString()));
        assertThat(testee().getSecretLink(newCalendarUrl, SESSION).blockOptional()).isNotEmpty();
    }

    @Test
    default void getSecretLinkShouldReturnSameTokenIfAlreadyGenerated() {
        SecretLinkToken secretLinkToken = testee().generateSecretLink(CALENDAR_URL, SESSION).block();

        for (int i = 0; i < 3; i++) {
            assertThat(testee().getSecretLink(CALENDAR_URL, SESSION).block()).isEqualTo(secretLinkToken);
        }
    }

    @Test
    default void getSecretLinkShouldBeIsolatedPerUser() {
        SecretLinkToken secretLinkToken = testee().getSecretLink(CALENDAR_URL, SESSION).block();
        SecretLinkToken secretLinkToken2 = testee().getSecretLink(CALENDAR_URL, SESSION_2).block();

        assertThat(secretLinkToken).isNotEqualTo(secretLinkToken2);
    }

    @Test
    default void getSecretLinkShouldBeIsolatedPerCalendarUrl() {
        SecretLinkToken secretLinkToken = testee().getSecretLink(CALENDAR_URL, SESSION).block();
        SecretLinkToken secretLinkToken2 = testee().getSecretLink(CALENDAR_URL_2, SESSION).block();

        assertThat(secretLinkToken).isNotEqualTo(secretLinkToken2);
    }

    @Test
    default void getSecretLinkShouldFailIfHasNoPermission() {
        setPermissionChecker(false);
        assertThatThrownBy(() -> testee().getSecretLink(CALENDAR_URL, SESSION).block())
            .isInstanceOf(SecretLinkPermissionException.class);
    }

    @Test
    default void generateSecretLinkShouldFailIfHasNoPermission() {
        setPermissionChecker(false);
        assertThatThrownBy(() -> testee().generateSecretLink(CALENDAR_URL, SESSION).block())
            .isInstanceOf(SecretLinkPermissionException.class);
    }

    @Test
    default void checkSecretLinkValidShouldReturnUsernameWhenTokenCheck() {
        SecretLinkToken secretLinkToken = testee().getSecretLink(CALENDAR_URL, SESSION).block();
        assertThat(testee().checkSecretLink(CALENDAR_URL, secretLinkToken).block()).isEqualTo(USERNAME);
    }

    @Test
    default void checkSecretLinkValidShouldReturnEmptyWhenTokenCheckInvalid() {
        assertThat(testee().checkSecretLink(CALENDAR_URL, new SecretLinkToken(UUID.randomUUID().toString())).blockOptional())
            .isEmpty();
    }


    @Test
    default void checkSecretLinkShouldReturnEmptyWhenURLDoesNotMatch() {
        SecretLinkToken secretLinkToken = testee().getSecretLink(CALENDAR_URL, SESSION).block();
        assertThat(testee().checkSecretLink(CALENDAR_URL_2, secretLinkToken).blockOptional()).isEmpty();
    }

}
