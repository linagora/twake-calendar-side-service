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

package com.linagora.calendar.dav;

import java.util.List;

public class DavExchangeNames {

    public static final List<String> ALL = List.of(
        "sabre:contact:created",
        "sabre:contact:deleted",
        "sabre:contact:updated",
        "sabre:contact:update",
        "calendar:subscription:created",
        "calendar:subscription:deleted",
        "calendar:subscription:updated",
        "calendar:calendar:created",
        "calendar:calendar:deleted",
        "calendar:calendar:updated",
        "calendar:event:reply",
        "sabre:addressbook:created",
        "sabre:addressbook:deleted",
        "sabre:addressbook:subscription:created",
        "sabre:addressbook:subscription:deleted",
        "sabre:addressbook:subscription:updated",
        "sabre:addressbook:updated",
        "calendar:event:alarm:created",
        "calendar:event:alarm:updated",
        "calendar:event:alarm:deleted",
        "calendar:event:alarm:cancel",
        "calendar:event:alarm:request",
        "calendar:calendar:created",
        "calendar:event:notificationEmail:send",
        "calendar:event:created",
        "calendar:event:updated",
        "calendar:event:deleted",
        "calendar:event:cancel",
        "calendar:event:request",
        "calendar:itip:deliver",
        "resource:calendar:event:created",
        "resource:calendar:event:accepted",
        "resource:calendar:event:declined");
}
