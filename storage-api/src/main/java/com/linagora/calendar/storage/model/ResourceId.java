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

package com.linagora.calendar.storage.model;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

public record ResourceId(String value) {

    public ResourceId {
        Preconditions.checkArgument(!StringUtils.isBlank(value), "resource id must not be empty");
    }

    public static ResourceId from(OpenPaaSId openPaaSId) {
        return new ResourceId(openPaaSId.value());
    }

    public OpenPaaSId asOpenPaaSId() {
        return new OpenPaaSId(value);
    }
}
