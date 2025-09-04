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

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.JMAPRoute;
import org.apache.james.jmap.JMAPRoutes;
import org.apache.james.metrics.api.MetricFactory;

import com.google.common.base.Splitter;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ResourceIconRoute implements JMAPRoutes {

    private static final String CONTENT_TYPE_SVG = "image/svg+xml";
    private static final String CACHE_CONTROL = "public, max-age=604800"; // 1 week
    private static final String ICON_PARAM = "icon";
    private static final Splitter COMMA_SPLITTER = Splitter.on(',')
        .trimResults()
        .omitEmptyStrings();

    private final Map<String, byte[]> icons;
    private final MetricFactory metricFactory;
    private final String appVersion;

    @Inject
    public ResourceIconRoute(MetricFactory metricFactory,
                             @Named(RESOURCES_ICONS_KEY) Map<String, byte[]> resourcesIcons) {
        this.icons = resourcesIcons;
        this.metricFactory = metricFactory;
        this.appVersion = UUID.randomUUID().toString();
    }

    Endpoint endpoint() {
        return new Endpoint(HttpMethod.GET, "/linagora.esn.resource/images/icon/{" + ICON_PARAM + "}.svg");
    }

    @Override
    public Stream<JMAPRoute> routes() {
        return Stream.of(
            JMAPRoute.builder()
                .endpoint(endpoint())
                .action((req, res) -> Mono.from(metricFactory.decoratePublisherWithTimerMetric(this.getClass().getSimpleName(), serveIcon(req, res))))
                .corsHeaders());
    }

    private Mono<Void> serveIcon(HttpServerRequest request, HttpServerResponse response) {
        String iconName = request.param(ICON_PARAM);
        if (StringUtils.isBlank(iconName)) {
            return response.status(HttpResponseStatus.BAD_REQUEST).send();
        }

        byte[] data = icons.get(iconName);
        if (data == null) {
            return response.status(HttpResponseStatus.NOT_FOUND).send();
        }

        String etag = buildEtag(iconName);
        String ifNoneMatch = request.requestHeaders().get("If-None-Match");

        if (StringUtils.isNotBlank(ifNoneMatch)
            && ("*".equals(ifNoneMatch.trim()) || COMMA_SPLITTER.splitToStream(ifNoneMatch).anyMatch(etag::equals))) {
            return response.status(HttpResponseStatus.NOT_MODIFIED).send();
        }

        return response.status(HttpResponseStatus.OK)
            .header("Content-Type", CONTENT_TYPE_SVG)
            .header("Cache-Control", CACHE_CONTROL)
            .header("ETag", etag)
            .header("X-Content-Type-Options", "nosniff")
            .sendByteArray(Mono.just(data))
            .then();
    }

    private String buildEtag(String iconName) {
        return "\"" + appVersion + "-" + iconName.toLowerCase(Locale.US) + "\"";
    }

}
