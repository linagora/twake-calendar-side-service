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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.james.core.MailAddress;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DavDomainMemberUpdateApplier {

    Mono<UpdateResult> apply(DomainMemberUpdate update);

    class Default implements DavDomainMemberUpdateApplier {

        public record ContactUpdateContext(AtomicInteger successCount,
                                           AtomicInteger failureCount,
                                           List<AddressBookContact> failureContacts) {
            public ContactUpdateContext() {
                this(new AtomicInteger(0),
                    new AtomicInteger(0),
                    Collections.synchronizedList(new ArrayList<>()));
            }

            public void recordFailure(AddressBookContact contact) {
                failureCount.incrementAndGet();
                failureContacts.add(contact);
            }

            public void recordSuccess() {
                successCount.incrementAndGet();
            }
        }

        enum OperationType {
            ADD, UPDATE, DELETE
        }

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);

        private final CardDavClient davClient;
        private final OpenPaaSId domainId;

        public Default(CardDavClient davClient,
                       OpenPaaSId domainId) {
            this.davClient = davClient;
            this.domainId = domainId;
        }

        @Override
        public Mono<UpdateResult> apply(DomainMemberUpdate memberUpdate) {
            ContactUpdateContext deleteContext = new ContactUpdateContext();
            ContactUpdateContext updateContext = new ContactUpdateContext();
            ContactUpdateContext addContext = new ContactUpdateContext();

            return Mono.when(
                    processContacts(memberUpdate.deleted(), deleteContactOperation(), deleteContext, OperationType.DELETE),
                    processContacts(memberUpdate.updated(), upsertContactOperation(), updateContext, OperationType.UPDATE),
                    processContacts(memberUpdate.added(), upsertContactOperation(), addContext, OperationType.ADD))
                .then(Mono.defer(() -> Mono.just(toUpdateResult(addContext, updateContext, deleteContext))));
        }

        @FunctionalInterface
        interface ContactOperation {
            Mono<Void> process(AddressBookContact contact);
        }

        private ContactOperation upsertContactOperation() {
            return contact -> davClient.upsertContactDomainMembers(domainId, contact.vcardUid(), contact.toVcardBytes());
        }

        private ContactOperation deleteContactOperation() {
            return contact -> davClient.deleteContactDomainMembers(domainId, contact.vcardUid());
        }

        private Mono<Void> processContacts(Iterable<AddressBookContact> contacts,
                                           ContactOperation operation,
                                           ContactUpdateContext context,
                                           OperationType type) {

            return Flux.fromIterable(contacts)
                .flatMap(contact ->
                    operation.process(contact)
                        .doOnSuccess(ignored -> context.recordSuccess())
                        .onErrorResume(e -> {
                            context.recordFailure(contact);
                            LOGGER.error("Failed to {} contact {} for domain {}",
                                type.name().toLowerCase(),
                                contact.mail().map(MailAddress::asString).orElse(null),
                                domainId.value(),
                                e);
                            return Mono.empty();
                        }), ReactorUtils.LOW_CONCURRENCY)
                .then();
        }

        private UpdateResult toUpdateResult(ContactUpdateContext addContext,
                                            ContactUpdateContext updateContext,
                                            ContactUpdateContext removeContext) {
            return new UpdateResult(addContext.successCount.get(),
                ImmutableList.copyOf(addContext.failureContacts),
                updateContext.successCount.get(),
                ImmutableList.copyOf(updateContext.failureContacts),
                removeContext.successCount.get(),
                ImmutableList.copyOf(removeContext.failureContacts));
        }

    }

    record UpdateResult(int addedCount,
                        List<AddressBookContact> addFailureContacts,
                        int updatedCount,
                        List<AddressBookContact> updateFailureContacts,
                        int deletedCount,
                        List<AddressBookContact> deleteFailureContacts) {

        public UpdateResult merge(UpdateResult other) {
            return new UpdateResult(
                this.addedCount + other.addedCount,
                ImmutableList.copyOf(Iterables.concat(this.addFailureContacts, other.addFailureContacts)),
                this.updatedCount + other.updatedCount,
                ImmutableList.copyOf(Iterables.concat(this.updateFailureContacts, other.updateFailureContacts)),
                this.deletedCount + other.deletedCount,
                ImmutableList.copyOf(Iterables.concat(this.deleteFailureContacts, other.deleteFailureContacts)));
        }

        public boolean hasFailures() {
            return addFailureCount() > 0 || updateFailureCount() > 0 || deleteFailureCount() > 0;
        }

        public int addFailureCount() {
            return addFailureContacts.size();
        }

        public int updateFailureCount() {
            return updateFailureContacts.size();
        }

        public int deleteFailureCount() {
            return deleteFailureContacts.size();
        }
    }
}
