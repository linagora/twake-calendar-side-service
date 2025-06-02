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

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.metrics.api.MetricFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.UploadedFileDAO;

import io.netty.handler.codec.http.HttpMethod;

public class ImportProxyRoute extends ImportRoute {

    @Inject
    public ImportProxyRoute(Authenticator authenticator, MetricFactory metricFactory,
                            UploadedFileDAO fileDAO, CalDavClient calDavClient, CardDavClient cardDavClient) {
        super(authenticator, metricFactory, fileDAO, calDavClient, cardDavClient);
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/linagora.esn.dav.import/api/import");
    }
}
