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

import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.model.MailboxConstants;

import com.google.common.annotations.VisibleForTesting;

public class MailboxSessionUtil {
    public static MailboxSession create(Username username) {
        return create(username, MailboxConstants.FOLDER_DELIMITER);
    }

    public static MailboxSession create(Username username, char folderDelimiter) {
        return create(username, MailboxSession.SessionId.of(ThreadLocalRandom.current().nextLong()), folderDelimiter);
    }

    @VisibleForTesting
    public static MailboxSession create(Username username, MailboxSession.SessionId sessionId,
                                        char folderDelimiter) {
        ArrayList<Locale> locales = new ArrayList<>();

        return new MailboxSession(
            sessionId,
            username,
            Optional.of(username),
            locales,
            folderDelimiter,
            MailboxSession.SessionType.User);
    }
}
