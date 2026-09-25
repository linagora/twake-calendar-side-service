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


package com.linagora.calendar.restapi;

import jakarta.inject.Inject;

import org.apache.james.mailbox.MailboxSession;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalDavClient.CalendarAccess;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.secretlink.SecretLinkPermissionChecker;

import reactor.core.publisher.Mono;

/**
 * Only lets a user get a secret link for a calendar they can currently read, as reported by the DAV server.
 */
public class DavSecretLinkPermissionChecker implements SecretLinkPermissionChecker {

    private final CalDavClient calDavClient;

    @Inject
    public DavSecretLinkPermissionChecker(CalDavClient calDavClient) {
        this.calDavClient = calDavClient;
    }

    @Override
    public Mono<Boolean> verifyPermissions(CalendarURL url, MailboxSession session) {
        return calDavClient.resolveCalendarAccess(session.getUser(), url)
            .map(access -> access != CalendarAccess.NOT_FOUND);
    }
}
