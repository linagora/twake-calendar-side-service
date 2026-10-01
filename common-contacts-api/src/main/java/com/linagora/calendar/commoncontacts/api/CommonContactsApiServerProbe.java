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

package com.linagora.calendar.commoncontacts.api;

import jakarta.inject.Inject;

import org.apache.james.util.Port;
import org.apache.james.utils.GuiceProbe;

public class CommonContactsApiServerProbe implements GuiceProbe {
    private final CommonContactsApiServer server;

    @Inject
    public CommonContactsApiServerProbe(CommonContactsApiServer server) {
        this.server = server;
    }

    public Port getPort() {
        return server.getPort();
    }
}
