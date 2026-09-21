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

import org.apache.james.core.MailAddress;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;
import com.linagora.calendar.webadmin.service.UnsentMailDeletionService;

public class UnsentMailDeletionTask implements Task {
    public record Details(Instant instant, long deletedCount, long failedCount,
                          Optional<String> sender,
                          Optional<String> recipient,
                          Optional<Integer> limit) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType DELETE_UNSENT_MAILS = TaskType.of("delete-unsent-mails");

    private final UnsentMailDeletionService deletionService;
    private final UnsentMailQuery query;
    private final UnsentMailDeletionService.Context context;

    public UnsentMailDeletionTask(UnsentMailDeletionService deletionService, UnsentMailQuery query) {
        this.deletionService = deletionService;
        this.query = query;
        this.context = new UnsentMailDeletionService.Context();
    }

    @Override
    public Result run() {
        return deletionService.delete(query, context).block();
    }

    @Override
    public TaskType type() {
        return DELETE_UNSENT_MAILS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        UnsentMailDeletionService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            snapshot.deletedCount(),
            snapshot.failedCount(),
            query.sender().map(MailAddress::asString),
            query.recipient().map(MailAddress::asString),
            query.limit()));
    }
}
