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

package com.linagora.calendar.webadmin.task;

public record LdapUsersImportRunningOptions(int usersPerSecond) {
    public static final int DEFAULT_USERS_PER_SECOND = 100;
    public static final LdapUsersImportRunningOptions DEFAULT = of(DEFAULT_USERS_PER_SECOND);

    public static LdapUsersImportRunningOptions of(int usersPerSecond) {
        return new LdapUsersImportRunningOptions(usersPerSecond);
    }
}
