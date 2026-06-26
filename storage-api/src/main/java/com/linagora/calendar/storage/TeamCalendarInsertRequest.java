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

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;

public record TeamCalendarInsertRequest(OpenPaaSDomain domain,
                                        String name,
                                        String displayName) {

    public TeamCalendarInsertRequest {
        Preconditions.checkNotNull(domain, "domain must not be null");
        Preconditions.checkArgument(!StringUtils.isBlank(name), "team calendar name must not be empty");
        Preconditions.checkArgument(!StringUtils.isBlank(displayName), "team calendar displayName must not be empty");
    }
}
