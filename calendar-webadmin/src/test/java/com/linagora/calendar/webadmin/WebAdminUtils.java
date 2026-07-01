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

package com.linagora.calendar.webadmin;

import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.WebAdminServer;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;

final class WebAdminUtils {

    static org.apache.james.webadmin.WebAdminUtils.Startable createWebAdminServer(Routes... routes) {
        return org.apache.james.webadmin.WebAdminUtils.createWebAdminServer(routes);
    }

    static RequestSpecBuilder buildRequestSpecification(WebAdminServer webAdminServer) {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
        return org.apache.james.webadmin.WebAdminUtils.buildRequestSpecification(webAdminServer);
    }

    private WebAdminUtils() {
    }
}
