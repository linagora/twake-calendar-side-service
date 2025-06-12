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

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.OpenPaaSDomain;
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

    public static LdapToDavDomainMembersSyncTask singleDomain(OpenPaaSDomain domain, LdapToDavDomainMembersSyncService syncService) {
        return new LdapToDavDomainMembersSyncTask(Optional.of(domain), syncService);
    }

    public static LdapToDavDomainMembersSyncTask allDomains(LdapToDavDomainMembersSyncService syncService) {
        return new LdapToDavDomainMembersSyncTask(Optional.empty(), syncService);
    }

    private final LdapToDavDomainMembersSyncService syncService;
    private final DavDomainMemberUpdateApplier.ContactUpdateContext context;
    private final Optional<OpenPaaSDomain> optionalDomain;

    public LdapToDavDomainMembersSyncTask(Optional<OpenPaaSDomain> optionalDomain,
                                          LdapToDavDomainMembersSyncService syncService) {
        this.syncService = syncService;
        this.context = new DavDomainMemberUpdateApplier.ContactUpdateContext();
        this.optionalDomain = optionalDomain;
    }

    @Override
    public Result run() {
        SyncProcessor syncProcessor = optionalDomain
            .map(this::singleDomainSyncProcessor)
            .orElseGet(this::allDomainsSyncProcessor);

        return syncProcessor.process()
            .map(updateResult -> {
                if (updateResult.hasFailures()) {
                    return Result.PARTIAL;
                }
                return Result.COMPLETED;
            }).block();
    }

    @FunctionalInterface
    interface SyncProcessor {
        Mono<DavDomainMemberUpdateApplier.UpdateResult> process();
    }

    private SyncProcessor singleDomainSyncProcessor(OpenPaaSDomain domain) {
        return () -> syncService.syncDomainMembers(domain, context);
    }

    private SyncProcessor allDomainsSyncProcessor() {
        return () -> syncService.syncDomainMembers(context);
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
