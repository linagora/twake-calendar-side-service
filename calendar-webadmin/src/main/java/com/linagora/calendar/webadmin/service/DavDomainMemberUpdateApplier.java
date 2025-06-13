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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.james.core.MailAddress;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface DavDomainMemberUpdateApplier {

    Mono<UpdateResult> apply(DomainMemberUpdate update, ContactUpdateContext context);

    default Mono<UpdateResult> apply(DomainMemberUpdate update) {
        return apply(update, new ContactUpdateContext());
    }

    record ContactUpdateContext(EnumMap<OperationType, OperationContext> context) {

        public record OperationContext(AtomicInteger successCount,
                                       List<AddressBookContact> failureContacts) {
            public OperationContext() {
                this(new AtomicInteger(0),
                    Collections.synchronizedList(new ArrayList<>()));
            }

            public void recordFailure(AddressBookContact contact) {
                failureContacts.add(contact);
            }

            public void recordSuccess() {
                successCount.incrementAndGet();
            }
        }

        public ContactUpdateContext() {
            this(new EnumMap<>(Map.of(
                OperationType.ADD, new OperationContext(),
                OperationType.UPDATE, new OperationContext(),
                OperationType.DELETE, new OperationContext()
            )));
        }

        public OperationContext get(OperationType type) {
            return context.get(type);
        }

        public void recordSuccess(OperationType type) {
            context.get(type).recordSuccess();
        }

        public void recordFailure(OperationType type, AddressBookContact contact) {
            context.get(type).recordFailure(contact);
        }

        public UpdateResult toUpdateResult() {
            OperationContext addOperationContext = this.get(OperationType.ADD);
            OperationContext updateOperationContext = this.get(OperationType.UPDATE);
            OperationContext deleteOperationContext = this.get(OperationType.DELETE);
            return new UpdateResult(addOperationContext.successCount.get(),
                ImmutableList.copyOf(addOperationContext.failureContacts),
                updateOperationContext.successCount.get(),
                ImmutableList.copyOf(updateOperationContext.failureContacts),
                deleteOperationContext.successCount.get(),
                ImmutableList.copyOf(deleteOperationContext.failureContacts));
        }
    }

    enum OperationType {
        ADD, UPDATE, DELETE
    }

    class Default implements DavDomainMemberUpdateApplier {

        private static final Logger LOGGER = LoggerFactory.getLogger(Default.class);

        private final CardDavClient davClient;
        private final OpenPaaSId domainId;

        public Default(CardDavClient davClient,
                       OpenPaaSId domainId) {
            this.davClient = davClient;
            this.domainId = domainId;
        }

        @Override
        public Mono<UpdateResult> apply(DomainMemberUpdate memberUpdate, ContactUpdateContext context) {
            return Mono.when(
                    processContacts(memberUpdate.deleted(), deleteContactOperation(), context, OperationType.DELETE),
                    processContacts(memberUpdate.updated(), upsertContactOperation(), context, OperationType.UPDATE),
                    processContacts(memberUpdate.added(), upsertContactOperation(), context, OperationType.ADD))
                .then(Mono.defer(() -> Mono.just(context.toUpdateResult())));
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
                        .doOnSuccess(ignored -> context.recordSuccess(type))
                        .onErrorResume(e -> {
                            context.recordFailure(type, contact);
                            LOGGER.error("Failed to {} contact {} for domain {}",
                                type.name().toLowerCase(),
                                contact.mail().map(MailAddress::asString).orElse(null),
                                domainId.value(),
                                e);
                            return Mono.empty();
                        }), ReactorUtils.LOW_CONCURRENCY)
                .then();
        }
    }

    record UpdateResult(int addedCount,
                        List<AddressBookContact> addFailureContacts,
                        int updatedCount,
                        List<AddressBookContact> updateFailureContacts,
                        int deletedCount,
                        List<AddressBookContact> deleteFailureContacts) {

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
