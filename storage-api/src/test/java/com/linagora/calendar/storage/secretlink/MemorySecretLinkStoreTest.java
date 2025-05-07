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

import org.apache.james.mailbox.MailboxSession;
import org.junit.jupiter.api.BeforeEach;

import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Mono;

public class MemorySecretLinkStoreTest implements SecretLinkStoreContract {

    public static class SecretLinkPermissionCheckerHelper implements SecretLinkPermissionChecker {

        private boolean permissionGranted = true;

        @Override
        public Mono<Boolean> verifyPermissions(CalendarURL url, MailboxSession session) {
            return Mono.just(permissionGranted);
        }

        public void setPermissionGranted(boolean value) {
            this.permissionGranted = value;
        }
    }

    private MemorySecretLinkStore secretLinkStore;

    private SecretLinkPermissionCheckerHelper secretLinkPermissionChecker;

    @BeforeEach
    void beforeEach() {
        secretLinkPermissionChecker = new SecretLinkPermissionCheckerHelper();
        secretLinkStore = new MemorySecretLinkStore(secretLinkPermissionChecker);
        setupBeforeEach();
    }

    @Override
    public SecretLinkStore testee() {
        return secretLinkStore;
    }

    @Override
    public void setPermissionChecker(boolean value) {
        secretLinkPermissionChecker.setPermissionGranted(value);
    }
}
