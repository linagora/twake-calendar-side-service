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

package com.linagora.calendar.saas;

import java.util.Objects;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.linagora.calendar.storage.UserSearchMode;

import reactor.core.publisher.Mono;

public class SaaSUserProvisioner {
    private static final Logger LOGGER = LoggerFactory.getLogger(SaaSUserProvisioner.class);

    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;
    private final DomainSettingsResolver domainSettingsResolver;
    private final UserNameResolver userNameResolver;
    private final UserContactResolver userContactResolver;

    @Inject
    public SaaSUserProvisioner(OpenPaaSUserDAO userDAO,
                               OpenPaaSDomainDAO domainDAO,
                               CardDavClient cardDavClient,
                               DomainSettingsResolver domainSettingsResolver,
                               UserNameResolver userNameResolver,
                               UserContactResolver userContactResolver) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
        this.domainSettingsResolver = domainSettingsResolver;
        this.userNameResolver = userNameResolver;
        this.userContactResolver = userContactResolver;
    }

    // The directory is looked up once, for both the names and the domain member card.
    public Mono<OpenPaaSUser> provisionUser(Username username) {
        return lookupDirectory(username)
            .flatMap(userContact -> registerUser(username, userContact.flatMap(UserContact::names))
                .flatMap(user -> addUserToDomainAddressBook(user.username(), userContact).thenReturn(user)));
    }

    // Adds the member card without registering the user. Directory failures are propagated, so that the
    // card is never written without the directory details.
    public Mono<Void> addToDomainAddressBook(Username username) {
        return userContactResolver.resolve(username)
            .flatMap(userContact -> addUserToDomainAddressBook(username, userContact));
    }

    // A directory failure does not block the provisioning: the user is then handled as unknown to the directory,
    // the next subscription message or LDAP sync filling its names and card.
    private Mono<Optional<UserContact>> lookupDirectory(Username username) {
        return userContactResolver.resolve(username)
            .onErrorResume(error -> {
                LOGGER.warn("Could not resolve the directory details of user {}, provisioning it without them",
                    username.asString(), error);
                return Mono.just(Optional.empty());
            });
    }

    private Mono<OpenPaaSUser> registerUser(Username username, Optional<UserNames> maybeNames) {
        return userDAO.retrieve(username)
            .flatMap(user -> reimportDisplayName(user, maybeNames))
            .switchIfEmpty(Mono.defer(() -> userDAO.add(username, maybeNames)
                .doOnSuccess(created -> LOGGER.info("Registered user {} from SaaS subscription", username.asString()))));
    }

    /**
     * Reimports the display name (first and last name) of an already registered user from the
     * user name resolver (typically LDAP), so a rename in the source directory is reflected on
     * the calendar side upon the next {@code settings} message. See issue #929.
     */
    public Mono<OpenPaaSUser> reimportDisplayName(OpenPaaSUser user) {
        return userNameResolver.resolve(user.username())
            .flatMap(maybeNames -> reimportDisplayName(user, maybeNames));
    }

    private Mono<OpenPaaSUser> reimportDisplayName(OpenPaaSUser user, Optional<UserNames> maybeNames) {
        return maybeNames
            .filter(names -> displayNameChanged(user, names))
            .map(names -> userDAO.update(user.id(), user.username(), names.firstname(), names.lastname())
                .doOnSuccess(ignored -> LOGGER.info("Updated display name of user {}", user.username().asString()))
                .thenReturn(new OpenPaaSUser(user.username(), user.id(), names.firstname(), names.lastname())))
            .orElseGet(() -> Mono.just(user));
    }

    private boolean displayNameChanged(OpenPaaSUser user, UserNames names) {
        return !Objects.equals(StringUtils.defaultString(user.firstname()), StringUtils.defaultString(names.firstname()))
            || !Objects.equals(StringUtils.defaultString(user.lastname()), StringUtils.defaultString(names.lastname()));
    }

    private Mono<Void> addUserToDomainAddressBook(Username username, Optional<UserContact> userContact) {
        return Mono.justOrEmpty(username.getDomainPart())
            .flatMap(domain -> domainSettingsResolver.resolveUserSearchMode(domain)
                .filter(mode -> mode != UserSearchMode.DISABLED)
                .flatMap(ignored -> domainDAO.retrieve(domain))
                .flatMap(openPaaSDomain -> upsertUserContact(openPaaSDomain, username, userContact)));
    }

    private Mono<Void> upsertUserContact(OpenPaaSDomain domain, Username username, Optional<UserContact> userContact) {
        return Mono.fromCallable(() -> toAddressBookContact(username, userContact))
            .flatMap(addressBookContact -> findDomainMemberCard(domain, username)
                .flatMap(existing -> existing
                    .map(card -> updateCard(domain, username, userContact, addressBookContact, card))
                    .orElseGet(() -> upsert(domain, username, addressBookContact.vcardUid(), addressBookContact))));
    }

    // The card of the member may have been written by the LDAP sync under another name: it is looked up by email so that
    // it gets updated rather than a second card added. Of several cards of the member, the first one is updated.
    private Mono<Optional<DomainMemberCard>> findDomainMemberCard(OpenPaaSDomain domain, Username username) {
        return Mono.fromCallable(username::asMailAddress)
            .flatMapMany(mail -> cardDavClient.findContactsDomainMembersByEmail(domain.id(), mail))
            .next()
            .map(Optional::of)
            .defaultIfEmpty(Optional.empty());
    }

    // The existing card is rewritten in place, keeping its name and uid, only when the directory details differ from it.
    // Without directory details, a card holding only the mail never overrides it, as it may be a fuller one.
    private Mono<Void> updateCard(OpenPaaSDomain domain, Username username, Optional<UserContact> userContact,
                                  AddressBookContact addressBookContact, DomainMemberCard card) {
        if (userContact.isEmpty() || addressBookContact.hasSameContentAs(card.contact())) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> toAddressBookContact(username, userContact, card.contact().uid()))
            .flatMap(updatedContact -> upsert(domain, username, card.vcardUid(), updatedContact));
    }

    private AddressBookContact toAddressBookContact(Username username, Optional<UserContact> userContact) throws Exception {
        return toAddressBookContact(username, userContact, Optional.empty());
    }

    private AddressBookContact toAddressBookContact(Username username, Optional<UserContact> userContact, Optional<String> uid) throws Exception {
        AddressBookContact.Builder builder = AddressBookContact.builder()
            .uid(uid)
            .mail(username.asMailAddress());
        userContact.ifPresent(builder::userContact);
        return builder.build();
    }

    private Mono<Void> upsert(OpenPaaSDomain domain, Username username, String vcardUid, AddressBookContact contact) {
        return cardDavClient.upsertContactDomainMembers(domain.id(), vcardUid, contact.toVcardBytes())
            .doOnSuccess(ignored -> LOGGER.info("Upserted user {} in domain member addressbook of domain {}",
                username.asString(), domain.domain().asString()));
    }
}
