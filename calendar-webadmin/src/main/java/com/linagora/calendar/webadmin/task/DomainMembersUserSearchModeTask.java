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
import java.util.concurrent.atomic.AtomicReference;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.UserSearchMode;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.ContactUpdateContext;
import com.linagora.calendar.webadmin.service.DomainMembersUserSearchModeService;
import com.linagora.calendar.webadmin.service.DomainMembersUserSearchModeService.PopulateContext;

import reactor.core.publisher.Mono;

/**
 * Started when the effective user search mode of a domain enters or leaves {@code enabled}: fills the
 * {@code domain-members} address book of the domain with its registered users, or clears it, according to the mode
 * the domain has when the task runs.
 */
public class DomainMembersUserSearchModeTask implements Task {

    public record Details(Instant timestamp,
                          String domain,
                          Optional<String> userSearchMode,
                          int populatedCount,
                          ImmutableList<String> populateFailureUsers,
                          int deletedCount,
                          ImmutableList<String> deleteFailureContacts) implements TaskExecutionDetails.AdditionalInformation {

        @Override
        public Instant timestamp() {
            return timestamp;
        }
    }

    public static final TaskType TASK_TYPE = TaskType.of("align-domain-members-with-user-search-mode");
    private static final Logger LOGGER = LoggerFactory.getLogger(DomainMembersUserSearchModeTask.class);

    private final OpenPaaSDomain domain;
    private final DomainMembersUserSearchModeService service;
    private final PopulateContext populateContext;
    private final ContactUpdateContext clearContext;
    private final AtomicReference<UserSearchMode> appliedMode;

    public DomainMembersUserSearchModeTask(OpenPaaSDomain domain, DomainMembersUserSearchModeService service) {
        this.domain = domain;
        this.service = service;
        this.populateContext = new PopulateContext();
        this.clearContext = new ContactUpdateContext();
        this.appliedMode = new AtomicReference<>();
    }

    @Override
    public Result run() {
        LOGGER.info("Aligning the domain members address book of domain {} with its user search mode", domain.domain().asString());
        return service.align(domain, populateContext, clearContext)
            .map(outcome -> {
                appliedMode.set(outcome.userSearchMode());
                if (outcome.hasFailures()) {
                    return Result.PARTIAL;
                }
                return Result.COMPLETED;
            })
            .onErrorResume(error -> {
                LOGGER.error("Failed to align the domain members address book of domain {}", domain.domain().asString(), error);
                return Mono.just(Result.PARTIAL);
            })
            .defaultIfEmpty(Result.COMPLETED)
            .block();
    }

    @Override
    public TaskType type() {
        return TASK_TYPE;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        DavDomainMemberUpdateApplier.UpdateResult clearResult = clearContext.toUpdateResult();
        return Optional.of(new Details(Clock.systemUTC().instant(),
            domain.domain().asString(),
            Optional.ofNullable(appliedMode.get()).map(UserSearchMode::serialize),
            populateContext.populatedCount(),
            populateContext.failedUsers().stream()
                .map(Username::asString)
                .collect(ImmutableList.toImmutableList()),
            clearResult.deletedCount(),
            clearResult.deleteFailureContacts().stream()
                .flatMap(contact -> contact.mail().stream())
                .map(MailAddress::asString)
                .collect(ImmutableList.toImmutableList())));
    }
}
