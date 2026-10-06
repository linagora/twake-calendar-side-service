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

import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.webadmin.service.DomainAddressBookClearService;

/**
 * Deletes the contacts of an address book owned by a domain, e.g. the domain address book, optionally restricted to
 * the contacts having a mail address within {@code sourceDomain}.
 */
public class DomainAddressBookClearTask implements Task {

    public record Details(Instant instant, String domain, String addressBookId, Optional<String> sourceDomain,
                          long deletedContactCount, long failedContactCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType CLEAR_DOMAIN_ADDRESS_BOOK = TaskType.of("domain-addressbook-clear");

    private final DomainAddressBookClearService clearService;
    private final OpenPaaSDomain domain;
    private final AddressBookURL addressBookURL;
    private final Optional<Domain> sourceDomain;
    private final DomainAddressBookClearService.Context context;

    public DomainAddressBookClearTask(DomainAddressBookClearService clearService, OpenPaaSDomain domain,
                                      AddressBookURL addressBookURL, Optional<Domain> sourceDomain) {
        this.clearService = clearService;
        this.domain = domain;
        this.addressBookURL = addressBookURL;
        this.sourceDomain = sourceDomain;
        this.context = new DomainAddressBookClearService.Context();
    }

    @Override
    public Result run() {
        return clearService.clearContacts(domain.id(), addressBookURL, sourceDomain, context).block();
    }

    @Override
    public TaskType type() {
        return CLEAR_DOMAIN_ADDRESS_BOOK;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        DomainAddressBookClearService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            domain.domain().asString(),
            addressBookURL.addressBookId(),
            sourceDomain.map(Domain::asString),
            snapshot.deletedCount(),
            snapshot.failedCount()));
    }
}
