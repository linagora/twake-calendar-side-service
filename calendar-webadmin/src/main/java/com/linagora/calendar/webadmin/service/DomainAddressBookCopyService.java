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

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.user.api.UsersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ldap.LdapDomainMemberProvider;
import com.linagora.calendar.storage.ldap.LdapFilter;
import com.linagora.calendar.storage.ldap.LdapUser;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Copies the users of a source domain into an address book owned by another domain, e.g. its domain address book,
 * so that the members of the destination domain can auto-complete the users of the source domain.
 *
 * <p>The users are listed from the {@link UsersRepository}, enriched with the names known by the {@link OpenPaaSUserDAO}.
 * When an LDAP filter is supplied, the users are instead listed from the LDAP - which is then required - and only those
 * matching the filter are copied.
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

    public static class LdapFilterNotSupportedException extends RuntimeException {
        public LdapFilterNotSupportedException() {
            super("Filtering users with an LDAP filter requires the LDAP users repository");
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainAddressBookCopyService.class);

    private final CardDavClient cardDavClient;
    private final UsersRepository usersRepository;
    private final OpenPaaSUserDAO userDAO;
    private final Optional<LdapDomainMemberProvider> ldapDomainMemberProvider;

    @Inject
    public DomainAddressBookCopyService(CardDavClient cardDavClient, UsersRepository usersRepository, OpenPaaSUserDAO userDAO,
                                        Optional<LdapDomainMemberProvider> ldapDomainMemberProvider) {
        this.cardDavClient = cardDavClient;
        this.usersRepository = usersRepository;
        this.userDAO = userDAO;
        this.ldapDomainMemberProvider = ldapDomainMemberProvider;
    }

    public boolean supportsLdapFilter() {
        return ldapDomainMemberProvider.isPresent();
    }

    public Mono<Task.Result> copyUsers(Domain sourceDomain, Optional<LdapFilter> ldapFilter,
                                       OpenPaaSId destinationDomainId, AddressBookURL addressBookURL, Context context) {
        return sourceDomainContacts(sourceDomain, ldapFilter)
            .concatMap(contact -> copyContact(destinationDomainId, addressBookURL, contact, context))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .onErrorResume(error -> {
                LOGGER.error("Copying the users of {} into address book {} failed", sourceDomain.asString(),
                    addressBookURL.asUri().toASCIIString(), error);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Flux<AddressBookContact> sourceDomainContacts(Domain sourceDomain, Optional<LdapFilter> ldapFilter) {
        return ldapFilter
            .map(filter -> ldapContacts(sourceDomain, filter))
            .orElseGet(() -> usersRepositoryContacts(sourceDomain));
    }

    private Flux<AddressBookContact> ldapContacts(Domain sourceDomain, LdapFilter ldapFilter) {
        return Mono.justOrEmpty(ldapDomainMemberProvider)
            .switchIfEmpty(Mono.error(LdapFilterNotSupportedException::new))
            .flatMapMany(provider -> provider.domainMembers(sourceDomain, Optional.of(ldapFilter)))
            .filter(ldapUser -> ldapUser.mail().isPresent())
            .map(this::fromLdapUser);
    }

    private AddressBookContact fromLdapUser(LdapUser ldapUser) {
        return DomainMemberUpdate.toAddressBookContact(ldapUser, computeUid(Optional.empty(), ldapUser.mail()));
    }

    private Flux<AddressBookContact> usersRepositoryContacts(Domain sourceDomain) {
        return Flux.from(usersRepository.listUsersOfADomainReactive(sourceDomain))
            .concatMap(this::fromUsername);
    }

    private Mono<AddressBookContact> fromUsername(Username username) {
        return Mono.fromCallable(username::asMailAddress)
            .flatMap(mailAddress -> userDAO.retrieve(username)
                .map(user -> fromOpenPaaSUser(mailAddress, user))
                .defaultIfEmpty(fromMailAddress(mailAddress)));
    }

    private AddressBookContact fromOpenPaaSUser(MailAddress mailAddress, OpenPaaSUser user) {
        return contactBuilder(mailAddress)
            .givenName(Optional.ofNullable(StringUtils.trimToNull(user.firstname())))
            .familyName(Optional.ofNullable(StringUtils.trimToNull(user.lastname())))
            .displayName(user.fullName())
            .build();
    }

    private AddressBookContact fromMailAddress(MailAddress mailAddress) {
        return contactBuilder(mailAddress)
            .displayName(mailAddress.asString())
            .build();
    }

    private AddressBookContact.Builder contactBuilder(MailAddress mailAddress) {
        return AddressBookContact.builder()
            .uid(computeUid(Optional.empty(), Optional.of(mailAddress)))
            .mail(mailAddress);
    }

    private Mono<Task.Result> copyContact(OpenPaaSId destinationDomainId, AddressBookURL addressBookURL,
                                          AddressBookContact contact, Context context) {
        return cardDavClient.upsertDomainContact(destinationDomainId, addressBookURL, contact.vcardUid(), contact.toVcardBytes())
            .then(Mono.fromCallable(() -> {
                context.copiedCount.incrementAndGet();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(error -> {
                LOGGER.warn("Copying contact {} into address book {} failed", contact.vcardUid(),
                    addressBookURL.asUri().toASCIIString(), error);
                context.failedCount.incrementAndGet();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}
