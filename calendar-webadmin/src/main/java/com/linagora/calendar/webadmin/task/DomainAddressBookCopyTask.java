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
import com.linagora.calendar.storage.ldap.LdapFilter;
import com.linagora.calendar.webadmin.service.DomainAddressBookCopyService;

/**
 * Copies the users of {@code sourceDomain}, optionally restricted to those matching an LDAP filter, into an address
 * book owned by a domain, e.g. the domain address book.
 */
public class DomainAddressBookCopyTask implements Task {

    public record Details(Instant instant, String domain, String addressBookId, String sourceDomain,
                          Optional<String> ldapFilter, long copiedContactCount,
                          long failedContactCount) implements TaskExecutionDetails.AdditionalInformation {
        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType COPY_DOMAIN_ADDRESS_BOOK = TaskType.of("domain-addressbook-copy");

    private final DomainAddressBookCopyService copyService;
    private final OpenPaaSDomain domain;
    private final AddressBookURL addressBookURL;
    private final Domain sourceDomain;
    private final Optional<LdapFilter> ldapFilter;
    private final DomainAddressBookCopyService.Context context;

    public DomainAddressBookCopyTask(DomainAddressBookCopyService copyService, OpenPaaSDomain domain,
                                     AddressBookURL addressBookURL, Domain sourceDomain, Optional<LdapFilter> ldapFilter) {
        this.copyService = copyService;
        this.domain = domain;
        this.addressBookURL = addressBookURL;
        this.sourceDomain = sourceDomain;
        this.ldapFilter = ldapFilter;
        this.context = new DomainAddressBookCopyService.Context();
    }

    @Override
    public Result run() {
        return copyService.copyUsers(sourceDomain, ldapFilter, new DomainAddressBookCopyService.Destination(domain.id(), addressBookURL), context).block();
    }

    @Override
    public TaskType type() {
        return COPY_DOMAIN_ADDRESS_BOOK;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        DomainAddressBookCopyService.Context.Snapshot snapshot = context.snapshot();

        return Optional.of(new Details(Clock.systemUTC().instant(),
            domain.domain().asString(),
            addressBookURL.addressBookId(),
            sourceDomain.asString(),
            ldapFilter.map(LdapFilter::asString),
            snapshot.copiedCount(),
            snapshot.failedCount()));
    }
}
