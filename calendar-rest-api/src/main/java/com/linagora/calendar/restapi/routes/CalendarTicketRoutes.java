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

package com.linagora.calendar.restapi.routes;

import jakarta.inject.Inject;

import org.apache.james.jmap.http.Authenticator;

import com.linagora.tmail.james.jmap.ticket.TicketManager;
import com.linagora.tmail.james.jmap.ticket.TicketRoutes;

public class CalendarTicketRoutes extends TicketRoutes {

    @Inject
    public CalendarTicketRoutes(Authenticator authenticator,
                                TicketManager ticketManager) {
        super(authenticator, ticketManager);
    }

    @Override
    public String baseEndpoint() {
        return "ws/ticket";
    }
}
