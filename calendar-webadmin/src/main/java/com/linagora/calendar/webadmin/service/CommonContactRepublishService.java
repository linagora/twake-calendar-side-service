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


import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.task.Task;
import org.apache.james.task.TaskId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.MirrorAddressBooks;
import com.linagora.calendar.dav.SyncToken;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse.ContactObject;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncEndEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookSyncStartEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask.Scope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CommonContactRepublishService {

    private record AddressBookToRepublish(Audience audience, AddressBookURL url, Mono<SyncToken> syncToken,
                                          Mono<AddressBookReportXmlResponse> report) {
    }

    private record AddressBookSnapshot(Audience audience, AddressBookURL url, long revision, List<ContactObject> contacts) {
    }

    public static class Context {
        public record Snapshot(long processedContactCount, long failedContactCount, long failedAddressBookCount,
                               long failedUserCount, long failedDomainCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("processedContactCount", processedContactCount)
                    .add("failedContactCount", failedContactCount)
                    .add("failedAddressBookCount", failedAddressBookCount)
                    .add("failedUserCount", failedUserCount)
                    .add("failedDomainCount", failedDomainCount)
                    .toString();
            }
        }

        private final AtomicLong processedContactCount;
        private final AtomicLong failedContactCount;
        private final AtomicLong failedAddressBookCount;
        private final AtomicLong failedUserCount;
        private final AtomicLong failedDomainCount;

        public Context() {
            processedContactCount = new AtomicLong();
            failedContactCount = new AtomicLong();
            failedAddressBookCount = new AtomicLong();
            failedUserCount = new AtomicLong();
            failedDomainCount = new AtomicLong();
        }

        void incrementProcessedContact() {
            processedContactCount.incrementAndGet();
        }

        void incrementFailedContact() {
            failedContactCount.incrementAndGet();
        }

        void incrementFailedAddressBook() {
            failedAddressBookCount.incrementAndGet();
        }

        void incrementFailedUser() {
            failedUserCount.incrementAndGet();
        }

        void incrementFailedDomain() {
            failedDomainCount.incrementAndGet();
        }

        boolean hasFailures() {
            return failedContactCount.get() > 0
                || failedAddressBookCount.get() > 0
                || failedUserCount.get() > 0
                || failedDomainCount.get() > 0;
        }

        public Snapshot snapshot() {
            return new Snapshot(processedContactCount.get(),
                failedContactCount.get(),
                failedAddressBookCount.get(),
                failedUserCount.get(),
                failedDomainCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CommonContactRepublishService.class);
    private static final String TASK_NAME = CommonContactRepublishTask.REPUBLISH_COMMON_CONTACTS.asString();
    private static final int ADDRESS_BOOK_READ_AHEAD = 8;
    private static final int PUBLICATION_PREFETCH = 1;

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;
    private final CommonContactEventConverter converter;
    private final CommonContactPublisher publisher;

    @Inject
    public CommonContactRepublishService(OpenPaaSUserDAO userDAO,
                                         OpenPaaSDomainDAO domainDAO,
                                         CardDavClient cardDavClient,
                                         CommonContactEventConverter converter,
                                         CommonContactPublisher publisher) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
        this.converter = converter;
        this.publisher = publisher;
    }

    /**
     * Address books are published one at a time, so that the events of an address book, from its {@code SYNC_START}
     * to its {@code SYNC_END}, are not interleaved with the ones of another address book, and so that the rate of an
     * address book is the rate of the task. The next address books are read meanwhile.
     */
    public Mono<Task.Result> republish(TaskId taskId, Context context, CommonContactRepublishTask.RunningOptions runningOptions, Scope scope) {
        return addressBooks(context, scope)
            .flatMap(book -> readSnapshot(context, book), ADDRESS_BOOK_READ_AHEAD)
            .concatMap(snapshot -> republish(taskId, context, runningOptions, snapshot), PUBLICATION_PREFETCH)
            .then(Mono.fromCallable(() -> {
                Task.Result result = context.hasFailures() ? Task.Result.PARTIAL : Task.Result.COMPLETED;
                LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME, result, context.snapshot());
                return result;
            }))
            .onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME, e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    /**
     * The sync token is read before the contacts: a contact changed in between is republished with a revision lower
     * than the one of its live event, so that applications keep the live event. Read after the contacts, the token
     * would let a stale contact override a newer live event.
     */
    private Mono<AddressBookSnapshot> readSnapshot(Context context, AddressBookToRepublish book) {
        return book.syncToken()
            .switchIfEmpty(Mono.error(() -> new IllegalStateException("No sync token readable for address book " + book.url().asUri().toASCIIString())))
            .flatMap(syncToken -> book.report()
                .flatMap(response -> Mono.fromCallable(() -> new AddressBookSnapshot(book.audience(), book.url(), revision(syncToken),
                        contacts(book.url(), response)))
                    .subscribeOn(Schedulers.boundedElastic())))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for address book {}", TASK_NAME, book.url().asUri().toASCIIString(), e);
                context.incrementFailedAddressBook();
                return Mono.empty();
            });
    }

    /**
     * A contact listed without its data would be missing from the republication, which would then not be complete.
     */
    private List<ContactObject> contacts(AddressBookURL url, AddressBookReportXmlResponse response) {
        List<ContactObject> contacts = response.extractContactObjects();
        long listedContacts = response.countContacts();
        Preconditions.checkState(contacts.size() == listedContacts, "Address book %s lists %s contacts but returned the data of %s",
            url.asUri().toASCIIString(), listedContacts, contacts.size());
        return contacts;
    }

    private Mono<Void> republish(TaskId taskId, Context context, CommonContactRepublishTask.RunningOptions runningOptions,
                                 AddressBookSnapshot snapshot) {
        URI path = URI.create(StringUtils.removeStart(snapshot.url().asUri().toASCIIString(), "/"));
        String syncId = taskId.asString() + ":" + path;
        return publisher.publish(new AddressBookSyncStartEvent(snapshot.audience(), path, syncId, snapshot.revision()))
            .then(Flux.fromIterable(snapshot.contacts())
                .delayElements(delayBetweenContacts(runningOptions))
                .flatMap(contact -> republish(context, snapshot, contact, syncId), DEFAULT_CONCURRENCY)
                .count())
            .flatMap(republishedContacts -> publisher.publish(AddressBookSyncEndEvent.builder()
                .audience(snapshot.audience())
                .path(path)
                .syncId(syncId)
                .revision(snapshot.revision())
                .complete(republishedContacts == snapshot.contacts().size())
                .count(republishedContacts)
                .build()))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for address book {}", TASK_NAME, path, e);
                context.incrementFailedAddressBook();
                return Mono.empty();
            });
    }

    /**
     * A contact failing to be republished does not stop the address book: the other contacts are still republished,
     * and the {@code SYNC_END} event tells the republication is not complete.
     */
    private Mono<ContactObject> republish(Context context, AddressBookSnapshot snapshot, ContactObject contact, String syncId) {
        String path = contactPath(contact.href());
        return Mono.fromCallable(() -> converter.convert(Action.ADD, snapshot.audience(), path, contact.cardData(),
                    snapshot.revision(), contact.etag())
                .withSyncId(syncId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(publisher::publish)
            .then(Mono.fromCallable(() -> {
                context.incrementProcessedContact();
                return contact;
            }))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for contact {}", TASK_NAME, path, e);
                context.incrementFailedContact();
                return Mono.empty();
            });
    }

    private Duration delayBetweenContacts(CommonContactRepublishTask.RunningOptions runningOptions) {
        return Duration.ofNanos(Math.ceilDiv(Duration.ofSeconds(1).toNanos(), runningOptions.contactsPerSecond()));
    }

    private Flux<AddressBookToRepublish> addressBooks(Context context, Scope scope) {
        return switch (scope) {
            case Scope.All all -> switch (all.selection()) {
                case USERS -> allUserAddressBooks(context);
                case DOMAIN_ADDRESS_BOOKS -> allDomainAddressBooks(context);
                case BOTH -> Flux.concat(allUserAddressBooks(context), allDomainAddressBooks(context));
            };
            case Scope.ForUser forUser -> userAddressBooks(context, forUser.user());
            case Scope.ForDomain forDomain -> switch (forDomain.selection()) {
                case USERS -> usersOfDomainAddressBooks(context, forDomain.domain());
                case DOMAIN_ADDRESS_BOOKS -> domainAddressBooks(context, forDomain.domain());
                case BOTH -> Flux.concat(usersOfDomainAddressBooks(context, forDomain.domain()),
                    domainAddressBooks(context, forDomain.domain()));
            };
        };
    }

    private Flux<AddressBookToRepublish> allUserAddressBooks(Context context) {
        return userDAO.list()
            .flatMap(user -> userAddressBooks(context, user), DEFAULT_CONCURRENCY);
    }

    private Flux<AddressBookToRepublish> usersOfDomainAddressBooks(Context context, OpenPaaSDomain domain) {
        return userDAO.listByDomain(domain.domain())
            .flatMap(user -> userAddressBooks(context, user), DEFAULT_CONCURRENCY);
    }

    private Flux<AddressBookToRepublish> userAddressBooks(Context context, OpenPaaSUser user) {
        Audience audience = new Audience.User(user.username());
        return cardDavClient.listUserAddressBookUrls(user.username(), user.id(), MirrorAddressBooks.EXCLUDE)
            .map(addressBookURL -> new AddressBookToRepublish(audience, addressBookURL,
                Mono.defer(() -> cardDavClient.retrieveSyncToken(user.username(), addressBookURL)),
                Mono.defer(() -> cardDavClient.reportUserAddressBookContacts(user.username(), addressBookURL))))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME, user.username().asString(), e);
                context.incrementFailedUser();
                return Flux.empty();
            });
    }

    private Flux<AddressBookToRepublish> allDomainAddressBooks(Context context) {
        return domainDAO.list()
            .flatMap(domain -> domainAddressBooks(context, domain), DEFAULT_CONCURRENCY);
    }

    private Flux<AddressBookToRepublish> domainAddressBooks(Context context, OpenPaaSDomain domain) {
        Audience audience = new Audience.Domain(domain.domain());
        return cardDavClient.listDomainAddressBookUrls(domain.id(), MirrorAddressBooks.EXCLUDE)
            .map(addressBookURL -> new AddressBookToRepublish(audience, addressBookURL,
                Mono.defer(() -> cardDavClient.retrieveDomainAddressBookSyncToken(domain.id(), addressBookURL)),
                Mono.defer(() -> cardDavClient.reportDomainAddressBookContacts(domain.id(), addressBookURL))))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for domain {}", TASK_NAME, domain.domain().asString(), e);
                context.incrementFailedDomain();
                return Flux.empty();
            });
    }

    private long revision(SyncToken syncToken) {
        return Long.parseLong(syncToken.value());
    }

    private String contactPath(URI href) {
        return StringUtils.removeStart(href.toASCIIString(), "/");
    }
}
