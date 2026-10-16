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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse.ContactObject;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.saas.contact.CommonContactSyncEvent;
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

    public record ContactToRepublish(Audience audience, String path, String cardData) {
    }

    private record AddressBookToRepublish(Audience audience, AddressBookURL url, Mono<AddressBookReportXmlResponse> report) {
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

    public Mono<Task.Result> republish(TaskId taskId, Context context, CommonContactRepublishTask.RunningOptions runningOptions, Scope scope) {
        return addressBooks(context, scope)
            // Keep the existing task-wide rate limit: one address book publishes at a time.
            .concatMap(book -> republishAddressBook(taskId, context, runningOptions, book))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> {
                Task.Result taskResult = context.hasFailures() ? Task.Result.PARTIAL : result;
                LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME, taskResult, context.snapshot());
                return taskResult;
            })
            .onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME, e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> republishAddressBook(TaskId taskId, Context context,
                                                  CommonContactRepublishTask.RunningOptions options, AddressBookToRepublish book) {
        return Mono.defer(() -> {
            String path = contactPath(book.url().asUri());
            String syncId = CommonContactSyncIdFactory.create(taskId, book.url());
            URI addressBookPath = URI.create(path);
            AtomicLong publishedCount = new AtomicLong();
            return publisher.publish(CommonContactSyncEvent.start(book.audience(), addressBookPath, syncId))
                .then(book.report().flatMap(response -> republishContacts(context, options, book, response, syncId, publishedCount)))
                .then(Mono.defer(() -> publisher.publish(CommonContactSyncEvent.end(book.audience(), addressBookPath, syncId,
                    true, publishedCount.get()))))
                .thenReturn(Task.Result.COMPLETED)
                .onErrorResume(e -> {
                    LOGGER.error("Error while doing task {} for address book {} (syncId={})", TASK_NAME, path, syncId, e);
                    context.incrementFailedAddressBook();
                    // A failed END is best effort; consumers must expire unfinished syncs without deleting contacts.
                    return publisher.publish(CommonContactSyncEvent.end(book.audience(), addressBookPath, syncId, false, publishedCount.get()))
                        .thenReturn(Task.Result.PARTIAL)
                        .onErrorResume(endError -> {
                            LOGGER.error("Unable to close contact sync {} for address book {}", syncId, path, endError);
                            return Mono.just(Task.Result.PARTIAL);
                        });
                });
        });
    }

    private Mono<Void> republishContacts(Context context, CommonContactRepublishTask.RunningOptions options,
                                                AddressBookToRepublish book, AddressBookReportXmlResponse response,
                                                String syncId, AtomicLong publishedCount) {
        return Mono.fromCallable(() -> {
                List<ContactObject> contacts = response.extractCompleteContactObjects();
                // A 207 can contain failed resources. Never certify a snapshot with silently omitted contacts.
                Preconditions.checkState(contacts.size() == response.countContacts(), "Incomplete address book REPORT for %s", book.url());
                String bookPath = contactPath(book.url().asUri());
                Set<String> paths = new HashSet<>();
                for (ContactObject contact : contacts) {
                    String path = contactPath(contact.href());
                    Preconditions.checkState(bookPath.equals(StringUtils.substringBeforeLast(path, "/")) && paths.add(path),
                        "Unexpected or duplicate contact path %s in address book %s", path, bookPath);
                }
                return contacts;
            })
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)
            .map(contact -> new ContactToRepublish(book.audience(), contactPath(contact.href()), contact.cardData()))
            // Unlike the generic throttler, this must propagate REPORT/validation failures to SYNC_END.
            .delayElements(Duration.ofNanos(Math.ceilDiv(Duration.ofSeconds(1).toNanos(), options.contactsPerSecond())))
            // Wait for each publication so a failed contact stops the book before the next one starts.
            .concatMap(contact -> republish(context, contact, syncId)
                .doOnSuccess(unused -> publishedCount.incrementAndGet()))
            .then();
    }

    private Mono<Void> republish(Context context, ContactToRepublish contact, String syncId) {
        return Mono.fromCallable(() -> converter.convert(Action.ADD, contact.audience(), contact.path(), contact.cardData()).withSyncId(syncId))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(publisher::publish)
            .doOnSuccess(unused -> context.incrementProcessedContact())
            .onErrorMap(e -> {
                context.incrementFailedContact();
                return new IllegalStateException("Unable to republish contact " + contact.path() + " in sync " + syncId, e);
            });
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
        return cardDavClient.listUserAddressBookUrls(user.username(), user.id(), MirrorAddressBooks.EXCLUDE)
            .distinct()
            .map(url -> new AddressBookToRepublish(new Audience.User(user.username()), url,
                Mono.defer(() -> cardDavClient.reportUserAddressBookContacts(user.username(), url))))
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
        return cardDavClient.listDomainAddressBookUrls(domain.id(), MirrorAddressBooks.EXCLUDE)
            .distinct()
            .map(url -> new AddressBookToRepublish(new Audience.Domain(domain.domain()), url,
                Mono.defer(() -> cardDavClient.reportDomainAddressBookContacts(domain.id(), url))))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for domain {}", TASK_NAME, domain.domain().asString(), e);
                context.incrementFailedDomain();
                return Flux.empty();
            });
    }

    private String contactPath(URI href) {
        return StringUtils.removeStart(href.getRawPath(), "/");
    }
}
