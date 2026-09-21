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


package com.linagora.calendar.webadmin.service;

import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.storage.unsent.UnsentMailRepository;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;

import reactor.core.publisher.Mono;

public class UnsentMailDeletionService {

    public static class Context {
        public record Snapshot(long deletedCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("deletedCount", deletedCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong deletedCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();

        void incrementDeleted() {
            deletedCount.incrementAndGet();
        }

        void incrementFailed() {
            failedCount.incrementAndGet();
        }

        public Snapshot snapshot() {
            return new Snapshot(deletedCount.get(), failedCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UnsentMailDeletionService.class);

    private final UnsentMailRepository repository;

    @Inject
    public UnsentMailDeletionService(UnsentMailRepository repository) {
        this.repository = repository;
    }

    public Mono<Task.Result> delete(UnsentMailQuery query, Context context) {
        return repository.list(query)
            .concatMap(id -> delete(id, context))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Mono<Task.Result> delete(UnsentMailId id, Context context) {
        return repository.delete(id)
            .then(Mono.fromCallable(() -> {
                context.incrementDeleted();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(error -> {
                LOGGER.warn("Deleting unsent mail {} failed", id.value(), error);
                context.incrementFailed();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}
