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

import java.util.function.Supplier;

import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpStatus;

import com.linagora.calendar.dav.DavClientException;

import spark.HaltException;

final class WebAdminRouteUtils {
    static <T> T wrapDavErrors(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DavClientException e) {
            throw serverError(e);
        }
    }

    static HaltException serverError(DavClientException e) {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
            .type(ErrorResponder.ErrorType.SERVER_ERROR)
            .message("Error while calling the DAV server")
            .cause(e)
            .haltError();
    }

    private WebAdminRouteUtils() {
    }
}
