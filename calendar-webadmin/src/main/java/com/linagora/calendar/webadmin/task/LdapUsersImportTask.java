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

import com.linagora.calendar.webadmin.service.LdapUsersImportService;

public class LdapUsersImportTask implements Task {
    public record Details(Instant instant, long processedUserCount, long failedUserCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType IMPORT_LDAP_USERS = TaskType.of("import-ldap-users");

    private final LdapUsersImportService service;
    private final LdapUsersImportRunningOptions runningOptions;
    private final LdapUsersImportService.Context context;

    public LdapUsersImportTask(LdapUsersImportService service, LdapUsersImportRunningOptions runningOptions) {
        this.service = service;
        this.runningOptions = runningOptions;
        this.context = new LdapUsersImportService.Context();
    }

    @Override
    public Result run() {
        return service.importUsers(context, runningOptions.usersPerSecond()).block();
    }

    @Override
    public TaskType type() {
        return IMPORT_LDAP_USERS;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        return Optional.of(new Details(Clock.systemUTC().instant(),
            context.snapshot().processedUserCount(),
            context.snapshot().failedUserCount()));
    }
}
