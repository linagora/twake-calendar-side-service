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

import static com.linagora.calendar.restapi.ResourceIconLoader.RESOURCES_ICONS_KEY;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.metrics.api.MetricFactory;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.UploadedFileDAO;

import io.netty.handler.codec.http.HttpMethod;

// Kept for backward compatibility with legacy OpenPaaS deployments.
@Deprecated
public final class LegacyRoutes {
    public static final AbstractModule MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            Multibinder<JMAPRoutes> routes = Multibinder.newSetBinder(binder(), JMAPRoutes.class);
            routes.addBinding().to(Import.class);
            routes.addBinding().to(ResourceIcon.class);
            routes.addBinding().to(Resource.class);
        }
    };

    private LegacyRoutes() {
    }

    static class Import extends ImportRoute {

        @Inject
        public Import(Authenticator authenticator, MetricFactory metricFactory,
                      UploadedFileDAO fileDAO, ImportProcessor importProcessor) {
            super(authenticator, metricFactory, fileDAO, importProcessor);
        }

        @Override
        Endpoint endpoint() {
            return new Endpoint(HttpMethod.POST, "/linagora.esn.dav.import/api/import");
        }
    }

    static class ResourceIcon extends ResourceIconRoute {

        @Inject
        public ResourceIcon(MetricFactory metricFactory,
                            @Named(RESOURCES_ICONS_KEY) Map<String, byte[]> resourcesIcons) {
            super(metricFactory, resourcesIcons);
        }

        @Override
        Endpoint endpoint() {
            return new Endpoint(HttpMethod.GET, "/linagora.esn.resource/images/icon/{icon}.svg");
        }
    }

    static class Resource extends ResourceRoute {

        @Inject
        public Resource(Authenticator authenticator,
                        MetricFactory metricFactory,
                        ResourceDAO resourceDAO,
                        OpenPaaSDomainDAO openPaaSDomainDAO,
                        OpenPaaSDomainAdminDAO domainAdminDAO) {
            super(authenticator, metricFactory, resourceDAO, openPaaSDomainDAO, domainAdminDAO);
        }

        @Override
        Endpoint endpoint() {
            return new Endpoint(HttpMethod.GET, "/linagora.esn.resource/api/resources/{resourceId}");
        }
    }

}
