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

import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Mono;

public interface SecretLinkPermissionChecker {

    class UnsafeSecretLinkPermissionChecker implements SecretLinkPermissionChecker {
        @Override
        public Mono<Boolean> verifyPermissions(CalendarURL url, MailboxSession session) {
            return Mono.just(true);
        }
    }

    default Mono<Void> assertPermissions(CalendarURL url, MailboxSession session) {
        return verifyPermissions(url, session)
            .flatMap(allowed -> {
                if (!allowed) {
                    return Mono.error(new SecretLinkPermissionException(url, session));
                }
                return Mono.empty();
            });
    }

    Mono<Boolean> verifyPermissions(CalendarURL url, MailboxSession session);

}
