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
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier;
import com.linagora.calendar.webadmin.service.DavDomainMembersClearService;

import reactor.core.publisher.Mono;

public class ClearDavDomainMembersTask implements Task {

    public record Details(Instant timestamp,
                          Optional<String> domain,
                          Optional<Set<String>> ignoredDomains,
                          int deletedCount,
                          ImmutableList<String> deleteFailureContacts) implements TaskExecutionDetails.AdditionalInformation {

        public static Details from(Optional<OpenPaaSDomain> domain,
                                   Optional<Set<Domain>> ignoredDomains,
                                   DavDomainMemberUpdateApplier.UpdateResult updateResult,
                                   Instant instant) {

            ImmutableList<String> deleteFailures = updateResult.deleteFailureContacts()
                .stream()
                .flatMap(contact -> contact.mail().stream())
                .map(MailAddress::asString)
                .collect(ImmutableList.toImmutableList());

            return new Details(instant,
                domain.map(OpenPaaSDomain::domain).map(Domain::asString),
                ignoredDomains.map(domains -> domains.stream()
                    .map(Domain::asString)
                    .collect(ImmutableSet.toImmutableSet())),
                updateResult.deletedCount(),
                deleteFailures);
        }

        @Override
        public Instant timestamp() {
            return timestamp;
        }
    }

    public static final TaskType TASK_TYPE = TaskType.of("clear-domain-members-contacts-dav");
    private static final Logger LOGGER = LoggerFactory.getLogger(ClearDavDomainMembersTask.class);

    public static ClearDavDomainMembersTask singleDomain(OpenPaaSDomain domain, DavDomainMembersClearService clearService,
                                                         OpenPaaSDomainDAO openPaaSDomainDAO) {
        return new ClearDavDomainMembersTask(new SingleDomain(domain), clearService, openPaaSDomainDAO);
    }

    public static ClearDavDomainMembersTask allDomains(DavDomainMembersClearService clearService,
                                                       OpenPaaSDomainDAO openPaaSDomainDAO, ImmutableSet<Domain> ignoredDomains) {
        return new ClearDavDomainMembersTask(new AllDomain(ignoredDomains), clearService, openPaaSDomainDAO);
    }

    public sealed interface ClearTaskRequest permits SingleDomain, AllDomain {
    }

    record SingleDomain(OpenPaaSDomain domain) implements ClearTaskRequest {
    }

    record AllDomain(ImmutableSet<Domain> ignoredDomains) implements ClearTaskRequest {
    }

    private final DavDomainMembersClearService clearService;
    private final DavDomainMemberUpdateApplier.ContactUpdateContext context;
    private final ClearTaskRequest clearTaskRequest;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;

    public ClearDavDomainMembersTask(ClearTaskRequest clearTaskRequest,
                                     DavDomainMembersClearService clearService,
                                     OpenPaaSDomainDAO openPaaSDomainDAO) {
        this.clearService = clearService;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.clearTaskRequest = clearTaskRequest;
        this.context = new DavDomainMemberUpdateApplier.ContactUpdateContext();
    }

    @Override
    public Result run() {
        return switch (clearTaskRequest) {
            case SingleDomain singleDomain -> {
                LOGGER.info("Starting domain members clear for single domain: {}",
                    singleDomain.domain().domain().asString());
                yield clearDomainMembers(singleDomain.domain()).block();
            }
            case AllDomain allDomains -> {
                LOGGER.info("Starting domain members clear for all domains, ignoredDomains={}",
                    allDomains.ignoredDomains().stream().map(Domain::asString).collect(ImmutableSet.toImmutableSet()));
                yield clearAllDomains(allDomains.ignoredDomains()).block();
            }
        };
    }

    private Mono<Result> clearAllDomains(ImmutableSet<Domain> ignoredDomains) {
        return openPaaSDomainDAO.list()
            .filter(openPaaSDomain -> !ignoredDomains.contains(openPaaSDomain.domain()))
            .concatMap(openPaaSDomain -> clearDomainMembers(openPaaSDomain)
                .onErrorResume(error -> {
                    LOGGER.error("Failed to clear domain members for domain: {}", openPaaSDomain.domain(), error);
                    return Mono.just(Result.PARTIAL);
                }))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Mono<Result> clearDomainMembers(OpenPaaSDomain domain) {
        return clearService.clearDomainMembers(domain, context)
            .map(updateResult -> {
                if (updateResult.hasFailures()) {
                    return Result.PARTIAL;
                }
                return Result.COMPLETED;
            });
    }

    @Override
    public TaskType type() {
        return TASK_TYPE;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        Optional<OpenPaaSDomain> targetDomain = switch (clearTaskRequest) {
            case SingleDomain singleDomain -> Optional.of(singleDomain.domain());
            case AllDomain ignored -> Optional.empty();
        };

        Optional<Set<Domain>> ignoredDomains = switch (clearTaskRequest) {
            case SingleDomain ignored -> Optional.empty();
            case AllDomain allDomains -> Optional.of(allDomains.ignoredDomains());
        };

        return Optional.of(Details.from(targetDomain, ignoredDomains, context.toUpdateResult(), Clock.systemUTC().instant()));
    }
}
