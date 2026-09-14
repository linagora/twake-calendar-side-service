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

import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.webadmin.service.CommonContactRepublishService;

public class CommonContactRepublishTask implements Task {
    public record Details(Instant instant, Optional<String> username, Optional<String> domain, Optional<String> scope,
                          long processedContactCount, long failedContactCount,
                          long failedAddressBookCount, long failedUserCount, long failedDomainCount,
                          int contactsPerSecond) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public sealed interface Scope {
        record All() implements Scope {
        }

        record SingleUser(OpenPaaSUser user) implements Scope {
        }

        record WholeDomain(OpenPaaSDomain domain) implements Scope {
        }

        record DomainAddressBooks(OpenPaaSDomain domain) implements Scope {
        }
    }

    public record RunningOptions(int contactsPerSecond) {
        public static final int DEFAULT_CONTACTS_PER_SECOND = 100;

        public RunningOptions {
            Preconditions.checkArgument(contactsPerSecond > 0, "contactsPerSecond must be strictly positive");
        }

        public static RunningOptions of(int contactsPerSecond) {
            return new RunningOptions(contactsPerSecond);
        }
    }

    public static final TaskType REPUBLISH_COMMON_CONTACTS = TaskType.of("republish-common-contacts");
    public static final String DOMAIN_SCOPE = "domain";

    private final CommonContactRepublishService republishService;
    private final RunningOptions runningOptions;
    private final Scope scope;
    private final CommonContactRepublishService.Context context;

    public CommonContactRepublishTask(CommonContactRepublishService republishService, RunningOptions runningOptions, Scope scope) {
        this.republishService = republishService;
        this.runningOptions = runningOptions;
        this.scope = scope;
        this.context = new CommonContactRepublishService.Context();
    }

    @Override
    public Result run() {
        return republishService.republish(context, runningOptions, scope).block();
    }

    @Override
    public TaskType type() {
        return REPUBLISH_COMMON_CONTACTS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        CommonContactRepublishService.Context.Snapshot snapshot = context.snapshot();
        return Optional.of(new Details(Clock.systemUTC().instant(),
            scopedUsername(),
            scopedDomain(),
            scopeParameter(),
            snapshot.processedContactCount(),
            snapshot.failedContactCount(),
            snapshot.failedAddressBookCount(),
            snapshot.failedUserCount(),
            snapshot.failedDomainCount(),
            runningOptions.contactsPerSecond()));
    }

    private Optional<String> scopeParameter() {
        return switch (scope) {
            case Scope.DomainAddressBooks ignored -> Optional.of(DOMAIN_SCOPE);
            default -> Optional.empty();
        };
    }

    private Optional<String> scopedUsername() {
        return switch (scope) {
            case Scope.SingleUser singleUser -> Optional.of(singleUser.user().username().asString());
            default -> Optional.empty();
        };
    }

    private Optional<String> scopedDomain() {
        return switch (scope) {
            case Scope.WholeDomain wholeDomain -> Optional.of(wholeDomain.domain().domain().asString());
            case Scope.DomainAddressBooks domainAddressBooks -> Optional.of(domainAddressBooks.domain().domain().asString());
            default -> Optional.empty();
        };
    }
}
