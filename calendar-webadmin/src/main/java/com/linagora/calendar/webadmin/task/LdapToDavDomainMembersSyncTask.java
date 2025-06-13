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
import java.util.function.Function;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier;
import com.linagora.calendar.webadmin.service.LdapToDavDomainMembersSyncService;

import reactor.core.publisher.Mono;

public class LdapToDavDomainMembersSyncTask implements Task {

    public record Details(Instant timestamp,
                          Optional<String> domain,
                          int addedCount,
                          ImmutableList<String> addFailureContacts,
                          int updatedCount,
                          ImmutableList<String> updateFailureContacts,
                          int deletedCount,
                          ImmutableList<String> deleteFailureContacts) implements TaskExecutionDetails.AdditionalInformation {

        public static Details from(Optional<OpenPaaSDomain> domain,
                                   DavDomainMemberUpdateApplier.UpdateResult updateResult,
                                   Instant instant) {

            Function<List<AddressBookContact>, ImmutableList<String>> getEmails = contactList -> contactList
                .stream()
                .flatMap(contact -> contact.mail().stream())
                .map(MailAddress::asString)
                .collect(ImmutableList.toImmutableList());

            return new Details(instant,
                domain.map(OpenPaaSDomain::domain).map(Domain::asString),
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
        return new LdapToDavDomainMembersSyncTask(Optional.of(domain), syncService, openPaaSDomainDAO);
    }

    public static LdapToDavDomainMembersSyncTask allDomains(LdapToDavDomainMembersSyncService syncService,
                                                            OpenPaaSDomainDAO openPaaSDomainDAO) {
        return new LdapToDavDomainMembersSyncTask(Optional.empty(), syncService, openPaaSDomainDAO);
    }

    private final LdapToDavDomainMembersSyncService syncService;
    private final DavDomainMemberUpdateApplier.ContactUpdateContext context;
    private final Optional<OpenPaaSDomain> optionalDomain;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;

    public LdapToDavDomainMembersSyncTask(Optional<OpenPaaSDomain> optionalDomain,
                                          LdapToDavDomainMembersSyncService syncService,
                                          OpenPaaSDomainDAO openPaaSDomainDAO) {
        this.syncService = syncService;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.context = new DavDomainMemberUpdateApplier.ContactUpdateContext();
        this.optionalDomain = optionalDomain;
    }

    @Override
    public Result run() {
        SyncProcessor syncProcessor = optionalDomain
            .map(this::singleDomainSyncProcessor)
            .orElseGet(this::allDomainsSyncProcessor);

        return syncProcessor.process().block();
    }

    @FunctionalInterface
    interface SyncProcessor {
        Mono<Result> process();
    }

    private SyncProcessor singleDomainSyncProcessor(OpenPaaSDomain domain) {
        return () -> syncDomainMembers(domain);
    }

    private SyncProcessor allDomainsSyncProcessor() {
        return () -> openPaaSDomainDAO.list()
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
        return Optional.of(Details.from(optionalDomain, context.toUpdateResult(), Clock.systemUTC().instant()));
    }
}
