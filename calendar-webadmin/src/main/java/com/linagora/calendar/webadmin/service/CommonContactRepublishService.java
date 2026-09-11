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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.task.Task;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.MirrorAddressBooks;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class CommonContactRepublishService {

    public record ContactToRepublish(Audience audience, String path, String cardData) {
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

    public Mono<Task.Result> republish(Context context, CommonContactRepublishTask.RunningOptions runningOptions) {
        return Flux.concat(userContacts(context), domainContacts(context))
            .transform(ReactorUtils.<ContactToRepublish, Task.Result>throttle()
                .elements(runningOptions.contactsPerSecond())
                .per(Duration.ofSeconds(1))
                .forOperation(contact -> republish(context, contact)))
            .reduce(Task.Result.COMPLETED, Task::combine)
            .map(result -> {
                if (context.hasFailures()) {
                    LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME, Task.Result.PARTIAL, context.snapshot());
                    return Task.Result.PARTIAL;
                }
                LOGGER.info("{} task result: {}. Detail:\n{}", TASK_NAME, result, context.snapshot());
                return result;
            })
            .onErrorResume(e -> {
                LOGGER.error("Task {} is incomplete", TASK_NAME, e);
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Mono<Task.Result> republish(Context context, ContactToRepublish contact) {
        return Mono.fromCallable(() -> converter.convert(Action.ADD, contact.audience(), contact.path(), contact.cardData()))
            .flatMap(publisher::publish)
            .then(Mono.fromCallable(() -> {
                context.incrementProcessedContact();
                return Task.Result.COMPLETED;
            }))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for contact {}", TASK_NAME, contact.path(), e);
                context.incrementFailedContact();
                return Mono.just(Task.Result.PARTIAL);
            });
    }

    private Flux<ContactToRepublish> userContacts(Context context) {
        return userDAO.list()
            .concatMap(user -> userContacts(context, user));
    }

    private Flux<ContactToRepublish> userContacts(Context context, OpenPaaSUser user) {
        return cardDavClient.listUserAddressBookUrls(user.username(), user.id(), MirrorAddressBooks.EXCLUDE)
            .concatMap(addressBookURL -> cardDavClient.exportContact(user.username(), addressBookURL)
                .flatMapMany(payload -> contacts(context, new Audience.User(user.username()), addressBookURL, payload)))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for user {}", TASK_NAME, user.username().asString(), e);
                context.incrementFailedUser();
                return Flux.empty();
            });
    }

    private Flux<ContactToRepublish> domainContacts(Context context) {
        return domainDAO.list()
            .concatMap(domain -> domainContacts(context, domain));
    }

    private Flux<ContactToRepublish> domainContacts(Context context, OpenPaaSDomain domain) {
        return cardDavClient.listDomainAddressBookUrls(domain.id(), MirrorAddressBooks.EXCLUDE)
            .concatMap(addressBookURL -> cardDavClient.exportDomainAddressBook(domain.id(), addressBookURL)
                .flatMapMany(payload -> contacts(context, new Audience.Domain(domain.domain()), addressBookURL, payload)))
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for domain {}", TASK_NAME, domain.domain().asString(), e);
                context.incrementFailedDomain();
                return Flux.empty();
            });
    }

    private Flux<ContactToRepublish> contacts(Context context, Audience audience, AddressBookURL addressBookURL, byte[] payload) {
        return Mono.fromCallable(() -> Ezvcard.parse(new String(payload, StandardCharsets.UTF_8)).all())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)
            .flatMap(vcard -> contact(context, audience, addressBookURL, vcard), DEFAULT_CONCURRENCY)
            .onErrorResume(e -> {
                LOGGER.error("Error while doing task {} for address book {}", TASK_NAME, addressBookURL.asUri().toASCIIString(), e);
                context.incrementFailedAddressBook();
                return Flux.empty();
            });
    }

    private Mono<ContactToRepublish> contact(Context context, Audience audience, AddressBookURL addressBookURL, VCard vcard) {
        return Mono.justOrEmpty(contactUid(vcard))
            .map(uid -> new ContactToRepublish(audience, contactPath(addressBookURL, uid), asCardData(vcard)))
            .switchIfEmpty(Mono.fromRunnable(() -> {
                LOGGER.warn("Task {} skips a contact without UID in address book {}", TASK_NAME, addressBookURL.asUri().toASCIIString());
                context.incrementFailedContact();
            }));
    }

    private Optional<String> contactUid(VCard vcard) {
        return Optional.ofNullable(vcard.getUid())
            .map(ezvcard.property.Uid::getValue)
            .filter(StringUtils::isNotBlank);
    }

    private String contactPath(AddressBookURL addressBookURL, String contactUid) {
        return StringUtils.removeStart(addressBookURL.vcardUri(contactUid).toASCIIString(), "/");
    }

    private String asCardData(VCard vcard) {
        return Ezvcard.write(vcard)
            .version(Optional.ofNullable(vcard.getVersion()).orElse(VCardVersion.V4_0))
            .prodId(false)
            .go();
    }
}
