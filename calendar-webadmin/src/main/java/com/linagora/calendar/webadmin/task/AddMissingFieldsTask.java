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

import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.storage.MigrationResult;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

public class AddMissingFieldsTask implements Task {
    public record Details(Instant instant, long processedUsers, long upgradedUsers, long errorCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static class Context {
        private final AtomicReference<MigrationResult> result;

        public Context() {
            this.result = new AtomicReference<>(MigrationResult.empty());
        }

        public void update(MigrationResult migrationResult) {
            result.set(migrationResult);
        }

        public Details snapshot() {
            MigrationResult current = result.get();
            return new Details(Clock.systemUTC().instant(),
                current.processedUsers(),
                current.upgradedUsers(),
                current.errorCount());
        }
    }

    public static final TaskType ADD_MISSING_FIELDS = TaskType.of("add-missing-fields");

    private final OpenPaaSUserDAO userDAO;
    private final Context context;

    public AddMissingFieldsTask(OpenPaaSUserDAO userDAO) {
        this.userDAO = userDAO;
        this.context = new Context();
    }

    @Override
    public Result run() {
        try {
            MigrationResult result = userDAO.addMissingFields().block();
            context.update(result);
            return Result.COMPLETED;
        } catch (Exception e) {
            return Result.PARTIAL;
        }
    }

    @Override
    public TaskType type() {
        return ADD_MISSING_FIELDS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        return Optional.of(context.snapshot());
    }
}
