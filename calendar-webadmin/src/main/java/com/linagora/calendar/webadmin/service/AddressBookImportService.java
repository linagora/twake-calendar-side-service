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

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.importer.ContactToImport;
import com.linagora.calendar.storage.AddressBookURL;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Imports the contacts of a vCard document into an address book of the DAV server.
 *
 * <p>The contacts are parsed by {@link ContactToImport}, shared with the end user import API.
 */
public class AddressBookImportService {

    public static class Context {
        public record Snapshot(long importedCount, long failedCount) {
            @Override
            public String toString() {
                return MoreObjects.toStringHelper(this)
                    .add("importedCount", importedCount)
                    .add("failedCount", failedCount)
                    .toString();
            }
        }

        private final AtomicLong importedCount = new AtomicLong();
        private final AtomicLong failedCount = new AtomicLong();

        public Snapshot snapshot() {
            return new Snapshot(importedCount.get(), failedCount.get());
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AddressBookImportService.class);

    private final CardDavClient cardDavClient;

    @Inject
    public AddressBookImportService(CardDavClient cardDavClient) {
        this.cardDavClient = cardDavClient;
    }

    public Mono<Task.Result> importContacts(Username username, AddressBookURL addressBookURL,
                                            List<ContactToImport> contacts, Context context) {
        return Flux.fromIterable(contacts)
            .concatMap(contact -> importContact(username, addressBookURL, contact, context))
            .reduce(Task.Result.COMPLETED, Task::combine);
    }

    private Mono<Task.Result> importContact(Username username, AddressBookURL addressBookURL,
                                            ContactToImport contact, Context context) {
        return cardDavClient.upsertContact(username, addressBookURL, contact.resourceName(), contact.payload())
            .doOnSuccess(any -> context.importedCount.incrementAndGet())
            .thenReturn(Task.Result.COMPLETED)
            .onErrorResume(error -> {
                LOGGER.warn("Importing contact {} into address book {} of user {} failed",
                    contact.resourceName(), addressBookURL.asUri().toASCIIString(), username.asString(), error);
                context.failedCount.incrementAndGet();
                return Mono.just(Task.Result.PARTIAL);
            });
    }
}
