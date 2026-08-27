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

import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailId;
import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMailQuery;
import com.linagora.calendar.webadmin.service.UnsentMailResendService;

public class UnsentMailResendTask implements Task {
    public record Details(Instant instant, long sentCount, long failedCount,
                          Optional<String> unsentMailId,
                          Optional<String> sender,
                          Optional<String> recipient,
                          Optional<Integer> limit) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType RESEND_UNSENT_MAILS = TaskType.of("resend-unsent-mails");

    private final UnsentMailResendService resendService;
    private final UnsentMailQuery query;
    private final Optional<UnsentMailId> unsentMailId;
    private final UnsentMailResendService.Context context;

    public UnsentMailResendTask(UnsentMailResendService resendService, UnsentMailQuery query) {
        this(resendService, query, Optional.empty());
    }

    public UnsentMailResendTask(UnsentMailResendService resendService, UnsentMailId unsentMailId) {
        this(resendService, UnsentMailQuery.ALL, Optional.of(unsentMailId));
    }

    private UnsentMailResendTask(UnsentMailResendService resendService, UnsentMailQuery query,
                                 Optional<UnsentMailId> unsentMailId) {
        this.resendService = resendService;
        this.query = query;
        this.unsentMailId = unsentMailId;
        this.context = new UnsentMailResendService.Context();
    }

    @Override
    public Result run() {
        return unsentMailId
            .map(id -> resendService.resend(id, context))
            .orElseGet(() -> resendService.resend(query, context))
            .block();
    }

    @Override
    public TaskType type() {
        return RESEND_UNSENT_MAILS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        UnsentMailResendService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            snapshot.sentCount(),
            snapshot.failedCount(),
            unsentMailId.map(UnsentMailId::value),
            query.sender().map(MailAddress::asString),
            query.recipient().map(MailAddress::asString),
            query.limit()));
    }
}
