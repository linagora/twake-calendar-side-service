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

package com.linagora.calendar.twakespace;

import jakarta.inject.Inject;

import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;

import reactor.core.publisher.Mono;

// Restarts the consumer when its queue has none, like the side service does for its own consumers.
public class TwakeSpaceHealthCheck implements HealthCheck {
    static final ComponentName COMPONENT_NAME = new ComponentName("TwakeSpace");

    private final TwakeSpaceStartable startable;

    @Inject
    public TwakeSpaceHealthCheck(TwakeSpaceStartable startable) {
        this.startable = startable;
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Mono<Result> check() {
        return startable.consumersCheck()
            .map(consumersCheck -> Mono.from(consumersCheck.check()).map(TwakeSpaceHealthCheck::named))
            .orElseGet(() -> Mono.just(Result.degraded(COMPONENT_NAME, "The TwakeSpace consumer is not started")));
    }

    private static Result named(Result result) {
        return switch (result.getStatus()) {
            case HEALTHY -> Result.healthy(COMPONENT_NAME);
            case DEGRADED -> Result.degraded(COMPONENT_NAME, result.getCause().orElse(""));
            case UNHEALTHY -> result.getError()
                .map(error -> Result.unhealthy(COMPONENT_NAME, result.getCause().orElse(""), error))
                .orElseGet(() -> Result.unhealthy(COMPONENT_NAME, result.getCause().orElse("")));
        };
    }
}
