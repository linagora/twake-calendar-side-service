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

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse.ContactObject;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSId;

import ezvcard.Ezvcard;
import ezvcard.property.Email;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Deletes the contacts of an address book owned by a domain, e.g. the domain address book, optionally restricted to the
 * contacts having at least one mail address within a given source domain.
 */
public class DomainAddressBookClearService {

    public static class Context {
        public record Snapshot(long deletedCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("deletedCount", deletedCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong deletedCount;
        private final AtomicLong failedCount;

        public Context() {
            deletedCount = new AtomicLong();
            failedCount = new AtomicLong();
        }

        public Snapshot snapshot() {
            return new Snapshot(deletedCount.get(), failedCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainAddressBookClearService.class);

    private final CardDavClient cardDavClient;

    @Inject
    public DomainAddressBookClearService(CardDavClient cardDavClient) {
        this.cardDavClient = cardDavClient;
    }

    public Mono<Task.Result> clearContacts(OpenPaaSId domainId, AddressBookURL addressBookURL,
                                           Optional<Domain> sourceDomain, Context context) {
        return cardDavClient.reportDomainAddressBookContacts(domainId, addressBookURL)
            .map(AddressBookReportXmlResponse::extractContactObjects)
            .flatMapMany(Flux::fromIterable)
            .filter(contact -> sourceDomain.map(domain -> hasMailAddressIn(contact, domain)).orElse(true))
            .concatMap(contact -> deleteContact(domainId, contact, context))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .onErrorResume(error -> {
                LOGGER.error("Clearing contacts of address book {} failed", addressBookURL.asUri().toASCIIString(), error);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> deleteContact(OpenPaaSId domainId, ContactObject contact, Context context) {
        return cardDavClient.deleteDomainContact(domainId, contact.href())
            .then(Mono.fromCallable(() -> {
                context.deletedCount.incrementAndGet();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(error -> {
                LOGGER.warn("Deleting contact {} failed", contact.href().toASCIIString(), error);
                context.failedCount.incrementAndGet();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private boolean hasMailAddressIn(ContactObject contact, Domain domain) {
        String domainSuffix = "@" + domain.asString();
        return Ezvcard.parse(contact.cardData()).all().stream()
            .flatMap(vcard -> vcard.getEmails().stream())
            .map(Email::getValue)
            .map(StringUtils::trimToEmpty)
            .anyMatch(mailAddress -> Strings.CI.endsWith(mailAddress, domainSuffix));
    }
}
