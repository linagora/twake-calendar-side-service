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

import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.dav.importer.ContactToImport;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.webadmin.service.AddressBookImportService;

/**
 * Imports contacts into an address book owned by a domain rather than by a user, e.g. the domain address book.
 * The DAV writes are thus performed with the technical token of that domain.
 */
public class DomainAddressBookImportTask implements Task {

    public record Details(Instant instant, String domain, String addressBookId,
                          long totalContactCount, long importedContactCount,
                          long failedContactCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType IMPORT_DOMAIN_ADDRESS_BOOK = TaskType.of("domain-addressbook-import");

    private final AddressBookImportService importService;
    private final OpenPaaSDomain domain;
    private final AddressBookURL addressBookURL;
    private final List<ContactToImport> contacts;
    private final AddressBookImportService.Context context;

    public DomainAddressBookImportTask(AddressBookImportService importService, OpenPaaSDomain domain,
                                       AddressBookURL addressBookURL, List<ContactToImport> contacts) {
        this.importService = importService;
        this.domain = domain;
        this.addressBookURL = addressBookURL;
        this.contacts = contacts;
        this.context = new AddressBookImportService.Context();
    }

    @Override
    public Result run() {
        return importService.importContacts(domain.id(), addressBookURL, contacts, context).block();
    }

    @Override
    public TaskType type() {
        return IMPORT_DOMAIN_ADDRESS_BOOK;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        AddressBookImportService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            domain.domain().asString(),
            addressBookURL.addressBookId(),
            contacts.size(),
            snapshot.importedCount(),
            snapshot.failedCount()));
    }
}
