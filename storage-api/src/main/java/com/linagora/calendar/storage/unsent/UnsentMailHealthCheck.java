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


package com.linagora.calendar.storage.unsent;

import jakarta.inject.Inject;

import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;
import org.reactivestreams.Publisher;

import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

import reactor.core.publisher.Mono;

public class UnsentMailHealthCheck implements HealthCheck {

    public static final ComponentName COMPONENT_NAME = new ComponentName("UnsentMails");

    private final UnsentMailRepository unsentMailRepository;

    @Inject
    public UnsentMailHealthCheck(UnsentMailRepository unsentMailRepository) {
        this.unsentMailRepository = unsentMailRepository;
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Publisher<Result> check() {
        return unsentMailRepository.count(UnsentMailQuery.ALL)
            .map(count -> {
                if (count == 0) {
                    return Result.healthy(COMPONENT_NAME);
                }
                return Result.degraded(COMPONENT_NAME,
                    count + " mail(s) could not be delivered. Resend them with POST /unsentMails?action=resend");
            })
            .onErrorResume(e -> Mono.just(Result.unhealthy(COMPONENT_NAME, "Failed counting the unsent mails", e)));
    }
}
