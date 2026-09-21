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

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.webadmin.service.AddressBookImportService;
import com.linagora.calendar.webadmin.service.AddressBookImportService.ContactToImport;

public class AddressBookImportTask implements Task {

    public record Details(Instant instant, String username, String addressBookId,
                          long totalContactCount, long importedContactCount,
                          long failedContactCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType IMPORT_ADDRESS_BOOK = TaskType.of("addressbook-import");

    private final AddressBookImportService importService;
    private final Username username;
    private final AddressBookURL addressBookURL;
    private final List<ContactToImport> contacts;
    private final AddressBookImportService.Context context;

    public AddressBookImportTask(AddressBookImportService importService, Username username,
                                 AddressBookURL addressBookURL, List<ContactToImport> contacts) {
        this.importService = importService;
        this.username = username;
        this.addressBookURL = addressBookURL;
        this.contacts = contacts;
        this.context = new AddressBookImportService.Context();
    }

    @Override
    public Result run() {
        return importService.importContacts(username, addressBookURL, contacts, context).block();
    }

    @Override
    public TaskType type() {
        return IMPORT_ADDRESS_BOOK;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        AddressBookImportService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            username.asString(),
            addressBookURL.addressBookId(),
            contacts.size(),
            snapshot.importedCount(),
            snapshot.failedCount()));
    }
}
