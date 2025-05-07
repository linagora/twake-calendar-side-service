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

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.linagora.calendar.storage.CalendarURL;

import reactor.core.publisher.Mono;

public class MemorySecretLinkStore implements SecretLinkStore {

    private final Table<Username, CalendarURL, SecretLinkToken> store = Tables.synchronizedTable(HashBasedTable.create());
    private final SecretLinkPermissionChecker permissionChecker;

    public MemorySecretLinkStore(SecretLinkPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    @Override
    public Mono<SecretLinkToken> generateSecretLink(CalendarURL url, MailboxSession session) {
        return permissionChecker.assertPermissions(url, session)
            .then(Mono.fromCallable(SecretLinkToken::generate)
                .map(token -> {
                    store.put(session.getUser(), url, token);
                    return token;
                }));
    }

    @Override
    public Mono<SecretLinkToken> getSecretLink(CalendarURL url, MailboxSession session) {
        return permissionChecker.assertPermissions(url, session)
            .then(Mono.defer(() -> Mono.justOrEmpty(store.get(session.getUser(), url))))
            .switchIfEmpty(generateSecretLink(url, session));
    }
}
