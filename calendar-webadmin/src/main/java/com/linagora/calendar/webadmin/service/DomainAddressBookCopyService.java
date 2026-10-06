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

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.dav.AddressBookContact.computeUid;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ldap.LdapDomainMemberProvider;
import com.linagora.calendar.storage.ldap.LdapFilter;
import com.linagora.calendar.storage.ldap.LdapUser;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Copies the users of a source domain into an address book owned by another domain, e.g. its domain address book,
 * so that the members of the destination domain can auto-complete the users of the source domain.
 *
 * <p>The users are listed from the LDAP, hence this service is solely available when relying on the LDAP users repository.
 * When an LDAP filter is supplied, only the users matching it are copied.
 *
 * <p>The vCard UID derives from the user mail address, hence copying again updates the previously copied contacts.
 */
public class DomainAddressBookCopyService {

    public static class Context {
        public record Snapshot(long copiedCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("copiedCount", copiedCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong copiedCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();

        public Snapshot snapshot() {
            return new Snapshot(copiedCount.get(), failedCount.get());
        }
    }

    public record Destination(OpenPaaSId domainId, AddressBookURL addressBookURL) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainAddressBookCopyService.class);

    private final CardDavClient cardDavClient;
    private final LdapDomainMemberProvider ldapDomainMemberProvider;

    @Inject
    public DomainAddressBookCopyService(CardDavClient cardDavClient, LdapDomainMemberProvider ldapDomainMemberProvider) {
        this.cardDavClient = cardDavClient;
        this.ldapDomainMemberProvider = ldapDomainMemberProvider;
    }

    public Mono<Task.Result> copyUsers(Domain sourceDomain, Optional<LdapFilter> ldapFilter, Destination destination, Context context) {
        return sourceDomainContacts(sourceDomain, ldapFilter)
            .concatMap(contact -> copyContact(destination, contact, context))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .onErrorResume(error -> {
                LOGGER.error("Copying the users of {} into address book {} failed", sourceDomain.asString(),
                    destination.addressBookURL().asUri().toASCIIString(), error);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Flux<AddressBookContact> sourceDomainContacts(Domain sourceDomain, Optional<LdapFilter> ldapFilter) {
        return ldapDomainMemberProvider.domainMembers(sourceDomain, ldapFilter)
            .filter(ldapUser -> ldapUser.mail().isPresent())
            .map(this::fromLdapUser);
    }

    private AddressBookContact fromLdapUser(LdapUser ldapUser) {
        return DomainMemberUpdate.toAddressBookContact(ldapUser, computeUid(Optional.empty(), ldapUser.mail()));
    }

    private Mono<Task.Result> copyContact(Destination destination, AddressBookContact contact, Context context) {
        return cardDavClient.upsertDomainContact(destination.domainId(), destination.addressBookURL(), contact.vcardUid(), contact.toVcardBytes())
            .then(Mono.fromCallable(() -> {
                context.copiedCount.incrementAndGet();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(error -> {
                LOGGER.warn("Copying contact {} into address book {} failed", contact.vcardUid(),
                    destination.addressBookURL().asUri().toASCIIString(), error);
                context.failedCount.incrementAndGet();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}
