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

package com.linagora.calendar.dav;

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.DeleteUserDataTaskStep;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import ezvcard.Ezvcard;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class DavContactDeletionTaskStep implements DeleteUserDataTaskStep {

    private final CardDavClient cardDavClient;
    private final OpenPaaSUserDAO openPaaSUserDAO;

    @Inject
    public DavContactDeletionTaskStep(CardDavClient cardDavClient, OpenPaaSUserDAO openPaaSUserDAO) {
        this.cardDavClient = cardDavClient;
        this.openPaaSUserDAO = openPaaSUserDAO;
    }

    @Override
    public StepName name() {
        return new StepName("DavContactDeletionTaskStep");
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Mono<Void> deleteUserData(Username username) {
        return openPaaSUserDAO.retrieve(username)
            .flatMapMany(user -> cardDavClient.listUserAddressBookIds(username, user.id())
                .flatMap(addressBook -> {
                    if (addressBook.type() == CardDavClient.AddressBookType.SYSTEM) {
                        return deleteAllContactsInAddressBook(username, user.id(), addressBook.value());
                    } else {
                        return cardDavClient.deleteUserAddressBook(username, user.id(), addressBook.value()).then(Mono.empty());
                    }
                })
            ).then();
    }

    private Mono<Void> deleteAllContactsInAddressBook(Username username, OpenPaaSId userId, String addressBookId) {
        return cardDavClient.exportContact(username, userId, addressBookId)
            .flatMapMany(bytes -> Mono.fromCallable(() -> Ezvcard.parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).all())
                .flatMapMany(Flux::fromIterable)
                .map(vcard -> vcard.getUid().getValue())
                .flatMap(uid -> cardDavClient.deleteContact(username, userId, addressBookId, uid), DEFAULT_CONCURRENCY))
            .then();
    }
}
