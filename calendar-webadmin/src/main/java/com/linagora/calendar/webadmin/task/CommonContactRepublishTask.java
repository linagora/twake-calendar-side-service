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
import com.linagora.calendar.webadmin.service.CommonContactRepublishService;

public class CommonContactRepublishTask implements Task {
    public record Details(Instant instant, long processedContactCount, long failedContactCount,
                          long failedAddressBookCount, long failedUserCount, long failedDomainCount,
                          int contactsPerSecond) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
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
    private final CommonContactRepublishService.Context context;

    public CommonContactRepublishTask(CommonContactRepublishService republishService, RunningOptions runningOptions) {
        this.republishService = republishService;
        this.runningOptions = runningOptions;
        this.context = new CommonContactRepublishService.Context();
    }

    @Override
    public Result run() {
        return republishService.republish(context, runningOptions).block();
    }

    @Override
    public TaskType type() {
        return REPUBLISH_COMMON_CONTACTS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        CommonContactRepublishService.Context.Snapshot snapshot = context.snapshot();
        return Optional.of(new Details(Clock.systemUTC().instant(),
            snapshot.processedContactCount(),
            snapshot.failedContactCount(),
            snapshot.failedAddressBookCount(),
            snapshot.failedUserCount(),
            snapshot.failedDomainCount(),
            runningOptions.contactsPerSecond()));
    }
}
