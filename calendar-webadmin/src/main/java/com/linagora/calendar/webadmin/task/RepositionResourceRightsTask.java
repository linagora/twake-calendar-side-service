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

package com.linagora.calendar.webadmin.task;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import spark.Request;

public class RepositionResourceRightsTask implements Task {

    public static class Context {
        public record Snapshot(long processedResourceCount, long failedResourceCount) {
        }

        private final AtomicLong processedResourceCount = new AtomicLong();
        private final AtomicLong failedResourceCount = new AtomicLong();

        void incrementProcessed() {
            processedResourceCount.incrementAndGet();
        }

        void incrementFailed() {
            failedResourceCount.incrementAndGet();
        }

        long getProcessedResourceCount() {
            return processedResourceCount.get();
        }

        long getFailedResourceCount() {
            return failedResourceCount.get();
        }

        public Snapshot snapshot() {
            return new Snapshot(processedResourceCount.get(), failedResourceCount.get());
        }
    }

    public record Details(long processedResourceCount, long failedResourceCount, Instant timestamp) implements TaskExecutionDetails.AdditionalInformation {
    }

    public record RunningOptions(int resourcesPerSecond) {
        public static final RunningOptions DEFAULT = new RunningOptions(1);

        public static RunningOptions parse(Request request) {
            String param = request.queryParams("resourcesPerSecond");
            if (StringUtils.isBlank(param)) {
                return DEFAULT;
            }

            try {
                return new RunningOptions(Integer.parseInt(param));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid resourcesPerSecond: " + param, e);
            }
        }
    }

    public static final TaskType TYPE = TaskType.of("reposition-resource-rights");

    private static final Logger LOGGER = LoggerFactory.getLogger(RepositionResourceRightsTask.class);

    private final ResourceDAO resourceDAO;
    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final Context context;
    private final RunningOptions runningOptions;

    public RepositionResourceRightsTask(ResourceDAO resourceDAO,
                                        OpenPaaSUserDAO userDAO,
                                        CalDavClient calDavClient,
                                        RunningOptions runningOptions) {
        this.resourceDAO = resourceDAO;
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.context = new Context();
        this.runningOptions = runningOptions;
    }

    @Override
    public Result run() {
        return Flux.from(resourceDAO.findAll())
            .filter(resource -> !resource.deleted())
            .transform(ReactorUtils.<Resource, Task.Result>throttle()
                .elements(runningOptions.resourcesPerSecond())
                .per(Duration.ofSeconds(1))
                .forOperation(resource -> reapplyWriteRights(resource, context)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .doOnNext(result -> LOGGER.info("{} task result: {}. Processed: {}, Failed: {}", TYPE.asString(), result.name(), context.getProcessedResourceCount(), context.getFailedResourceCount()))
            .onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TYPE.asString(), e);
                return Mono.just(Task.Result.PARTIAL);
            })
            .block();
    }

    private Mono<Task.Result> reapplyWriteRights(Resource resource, Context context) {
        return resolveAdministratorUsernames(resource)
            .flatMap(usernames -> calDavClient.grantReadWriteRights(resource.domain(), resource.id(), usernames)
                .thenReturn(Result.COMPLETED)
                .onErrorResume(e -> {
                    LOGGER.error("Error granting rights for resource {}", resource.id().value(), e);
                    context.incrementFailed();
                    return Mono.just(Result.PARTIAL);
                })
                .doOnNext(result -> context.incrementProcessed()));
    }

    @Override
    public TaskType type() {
        return TYPE;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        Context.Snapshot snapshot = context.snapshot();
        return Optional.of(new Details(snapshot.processedResourceCount(), snapshot.failedResourceCount(), Clock.systemUTC().instant()));
    }

    private Mono<List<Username>> resolveAdministratorUsernames(Resource resource) {
        return Flux.fromIterable(resource.administrators())
            .flatMap(admin -> userDAO.retrieve(admin.refId()))
            .map(OpenPaaSUser::username)
            .collectList();
    }
}