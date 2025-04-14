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

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;

import com.linagora.calendar.storage.configuration.UserConfigurationDAO;

public interface UserConfigurationDAOContract {
    Domain DOMAIN = Domain.of("domain.tld");
    Username USERNAME = Username.fromLocalPartWithDomain("user", DOMAIN);
    Username USERNAME_2 = Username.fromLocalPartWithDomain("username", DOMAIN);
    MailboxSession MAILBOX_SESSION = MailboxSessionUtil.create(USERNAME);
    MailboxSession MAILBOX_SESSION_2 = MailboxSessionUtil.create(USERNAME_2);

    UserConfigurationDAO testee();
}