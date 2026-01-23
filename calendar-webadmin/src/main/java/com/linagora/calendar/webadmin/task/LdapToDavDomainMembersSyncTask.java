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
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier;
import com.linagora.calendar.webadmin.service.LdapToDavDomainMembersSyncService;

import reactor.core.publisher.Mono;

public class LdapToDavDomainMembersSyncTask implements Task {

    public record Details(Instant timestamp,
                          Optional<String> domain,
                          Optional<Set<String>> ignoredDomains,
                          int addedCount,
                          ImmutableList<String> addFailureContacts,
                          int updatedCount,
                          ImmutableList<String> updateFailureContacts,
                          int deletedCount,
                          ImmutableList<String> deleteFailureContacts) implements TaskExecutionDetails.AdditionalInformation {

        public static Details from(Optional<OpenPaaSDomain> domain,
                                   Optional<Set<Domain>> ignoredDomains,
                                   DavDomainMemberUpdateApplier.UpdateResult updateResult,
                                   Instant instant) {

            Function<List<AddressBookContact>, ImmutableList<String>> getEmails = contactList -> contactList
                .stream()
                .flatMap(contact -> contact.mail().stream())
                .map(MailAddress::asString)
                .collect(ImmutableList.toImmutableList());

            return new Details(instant,
                domain.map(OpenPaaSDomain::domain).map(Domain::asString),
                ignoredDomains.map(domains -> domains.stream()
                    .map(Domain::asString)
                    .collect(ImmutableSet.toImmutableSet())),
                updateResult.addedCount(),
                getEmails.apply(updateResult.addFailureContacts()),
                updateResult.updatedCount(),
                getEmails.apply(updateResult.updateFailureContacts()),
                updateResult.deletedCount(),
                getEmails.apply(updateResult.deleteFailureContacts()));
        }

        @Override
        public Instant timestamp() {
            return timestamp;
        }
    }

    public static final TaskType TASK_TYPE = TaskType.of("sync-domain-members-contacts-ldap-to-dav");
    private static final Logger LOGGER = LoggerFactory.getLogger(LdapToDavDomainMembersSyncTask.class);

    public static LdapToDavDomainMembersSyncTask singleDomain(OpenPaaSDomain domain, LdapToDavDomainMembersSyncService syncService,
                                                              OpenPaaSDomainDAO openPaaSDomainDAO) {
        return new LdapToDavDomainMembersSyncTask(new SingleDomain(domain), syncService, openPaaSDomainDAO);
    }

    public static LdapToDavDomainMembersSyncTask allDomains(LdapToDavDomainMembersSyncService syncService,
                                                            OpenPaaSDomainDAO openPaaSDomainDAO, ImmutableSet<Domain> ignoredDomains) {
        return new LdapToDavDomainMembersSyncTask(new AllDomain(ignoredDomains), syncService, openPaaSDomainDAO);
    }

    public sealed interface SyncTaskRequest permits SingleDomain, AllDomain {
    }

    record SingleDomain(OpenPaaSDomain domain) implements SyncTaskRequest {
    }

    record AllDomain(ImmutableSet<Domain> ignoredDomains) implements SyncTaskRequest {
    }

    private final LdapToDavDomainMembersSyncService syncService;
    private final DavDomainMemberUpdateApplier.ContactUpdateContext context;
    private final SyncTaskRequest syncTaskRequest;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;

    public LdapToDavDomainMembersSyncTask(SyncTaskRequest syncTaskRequest,
                                          LdapToDavDomainMembersSyncService syncService,
                                          OpenPaaSDomainDAO openPaaSDomainDAO) {
        this.syncService = syncService;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.syncTaskRequest = syncTaskRequest;
        this.context = new DavDomainMemberUpdateApplier.ContactUpdateContext();
    }

    @Override
    public Result run() {
        return switch (syncTaskRequest) {
            case SingleDomain singleDomain -> {
                LOGGER.info("Starting domain members sync for single domain: {}",
                    singleDomain.domain().domain().asString());
                yield singleDomainSyncProcessor(singleDomain.domain()).process().block();
            }
            case AllDomain allDomains -> {
                LOGGER.info("Starting domain members sync for all domains, ignoredDomains={}",
                    allDomains.ignoredDomains().stream().map(Domain::asString).collect(ImmutableSet.toImmutableSet()));
                yield allDomainsSyncProcessor(allDomains.ignoredDomains()).process().block();
            }
        };
    }

    @FunctionalInterface
    interface SyncProcessor {
        Mono<Result> process();
    }

    private SyncProcessor singleDomainSyncProcessor(OpenPaaSDomain domain) {
        return () -> syncDomainMembers(domain);
    }

    private SyncProcessor allDomainsSyncProcessor(ImmutableSet<Domain> ignoredDomains) {
        return () -> openPaaSDomainDAO.list()
            .filter(openPaaSDomain -> !ignoredDomains.contains(openPaaSDomain.domain()))
            .concatMap(openPaaSDomain -> syncDomainMembers(openPaaSDomain)
                .onErrorResume(error -> {
                    LOGGER.error("Failed to sync domain members for domain: {}", openPaaSDomain.domain(), error);
                    return Mono.just(Result.PARTIAL);
                }))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Mono<Result> syncDomainMembers(OpenPaaSDomain domain) {
        return syncService.syncDomainMembers(domain, context)
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
        Optional<OpenPaaSDomain> targetDomain = switch (syncTaskRequest) {
            case SingleDomain singleDomain -> Optional.of(singleDomain.domain());
            case AllDomain ignored -> Optional.empty();
        };

        Optional<Set<Domain>> ignoredDomains = switch (syncTaskRequest) {
            case SingleDomain ignored -> Optional.empty();
            case AllDomain allDomains -> Optional.of(allDomains.ignoredDomains());
        };

        return Optional.of(Details.from(targetDomain, ignoredDomains, context.toUpdateResult(), Clock.systemUTC().instant()));
    }
}
