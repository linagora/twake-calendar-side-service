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

package com.linagora.calendar.storage.mongodb;

import jakarta.inject.Inject;

import org.apache.james.core.healthcheck.ComponentName;
import org.apache.james.core.healthcheck.HealthCheck;
import org.apache.james.core.healthcheck.Result;
import org.bson.Document;
import org.reactivestreams.Publisher;

import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class MongoDBHealthCheck implements HealthCheck {

    public static final ComponentName COMPONENT_NAME = new ComponentName("MongoDB");

    private static final Document PING_COMMAND = new Document("ping", 1);

    private final MongoDatabase mongoDatabase;

    @Inject
    public MongoDBHealthCheck(MongoDatabase mongoDatabase) {
        this.mongoDatabase = mongoDatabase;
    }

    @Override
    public ComponentName componentName() {
        return COMPONENT_NAME;
    }

    @Override
    public Publisher<Result> check() {
        return Mono.from(mongoDatabase.runCommand(PING_COMMAND))
            .map(any -> Result.healthy(COMPONENT_NAME))
            .onErrorResume(e -> Mono.just(Result.unhealthy(COMPONENT_NAME, "Failed to execute request against MongoDB", e)));
    }
}
