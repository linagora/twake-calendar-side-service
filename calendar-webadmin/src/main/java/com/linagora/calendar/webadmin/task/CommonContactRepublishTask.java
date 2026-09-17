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
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        enum Selection {
            USERS(Optional.of("user")),
            DOMAIN_ADDRESS_BOOKS(Optional.of("domain")),
            BOTH(Optional.empty());

            public static Selection from(String queryParameter) {
                return Stream.of(values())
                    .filter(selection -> selection.queryParameter.filter(queryParameter::equals).isPresent())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported contact selection: " + queryParameter));
            }

            public static String supportedQueryParameters() {
                return Stream.of(values())
                    .flatMap(selection -> selection.queryParameter.stream())
                    .collect(Collectors.joining(", "));
            }

            private final Optional<String> queryParameter;

            Selection(Optional<String> queryParameter) {
                this.queryParameter = queryParameter;
            }

            public Optional<String> asQueryParameter() {
                return queryParameter;
            }
        }

        record All(Selection selection) implements Scope {
        }

        record ForUser(OpenPaaSUser user) implements Scope {
        }

        record ForDomain(OpenPaaSDomain domain, Selection selection) implements Scope {
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
            case Scope.All all -> all.selection().asQueryParameter();
            case Scope.ForDomain forDomain -> forDomain.selection().asQueryParameter();
            case Scope.ForUser ignored -> Optional.empty();
        };
    }

    private Optional<String> scopedUsername() {
        return switch (scope) {
            case Scope.ForUser forUser -> Optional.of(forUser.user().username().asString());
            default -> Optional.empty();
        };
    }

    private Optional<String> scopedDomain() {
        return switch (scope) {
            case Scope.ForDomain forDomain -> Optional.of(forDomain.domain().domain().asString());
            default -> Optional.empty();
        };
    }
}
