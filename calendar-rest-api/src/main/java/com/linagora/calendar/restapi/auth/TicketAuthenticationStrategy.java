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

package com.linagora.calendar.restapi.auth;

import org.apache.james.jmap.http.AuthenticationChallenge;
import org.apache.james.jmap.http.AuthenticationStrategy;
import org.apache.james.mailbox.MailboxSession;

import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;

public class TicketAuthenticationStrategy implements AuthenticationStrategy {

    @Override
    public Mono<MailboxSession> createMailboxSession(HttpServerRequest httpRequest) {
        return null;
    }

    @Override
    public AuthenticationChallenge correspondingChallenge() {
        return null;
    }

    @Override
    public String authHeaders(HttpServerRequest httpRequest) {
        return AuthenticationStrategy.super.authHeaders(httpRequest);
    }
}
