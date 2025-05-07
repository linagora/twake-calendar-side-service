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

public class SecretLinkPermissionException extends RuntimeException {

    public SecretLinkPermissionException(CalendarURL url, MailboxSession session) {
        super("User " + session.getUser() + " does not have permission to access secret link " + url.asUri());
    }

    public SecretLinkPermissionException(String message, Throwable cause) {
        super(message, cause);
    }
}
