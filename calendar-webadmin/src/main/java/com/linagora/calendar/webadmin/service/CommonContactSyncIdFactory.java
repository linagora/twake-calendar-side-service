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

package com.linagora.calendar.webadmin.service;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.task.TaskId;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.AddressBookURL;

public final class CommonContactSyncIdFactory {
    public static String create(TaskId taskId, AddressBookURL addressBookURL) {
        Preconditions.checkNotNull(taskId, "taskId must not be null");
        Preconditions.checkNotNull(addressBookURL, "addressBookURL must not be null");
        String canonicalPath = StringUtils.removeStart(addressBookURL.asUri().getRawPath(), "/");
        return taskId.asString() + ":" + canonicalPath;
    }

    private CommonContactSyncIdFactory() {
    }
}
