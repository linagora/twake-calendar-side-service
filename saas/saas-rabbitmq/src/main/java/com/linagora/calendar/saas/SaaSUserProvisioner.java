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
                .flatMap(user -> addUserToDomainAddressBook(user, userContact).thenReturn(user)));
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

    private Mono<OpenPaaSUser> registerUser(Username username, Optional<UserNames> names) {
        return userDAO.retrieve(username)
            .flatMap(user -> reimportDisplayName(user, names))
            .switchIfEmpty(Mono.defer(() -> userDAO.add(username, names)
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

    private Mono<Void> addUserToDomainAddressBook(OpenPaaSUser user, Optional<UserContact> userContact) {
        return Mono.justOrEmpty(user.username().getDomainPart())
            .flatMap(domain -> domainSettingsResolver.resolveUserSearchMode(domain)
                .filter(mode -> mode != UserSearchMode.DISABLED)
                .flatMap(ignored -> domainDAO.retrieve(domain))
                .flatMap(openPaaSDomain -> upsertUserContact(openPaaSDomain, user, userContact)));
    }

    private Mono<Void> upsertUserContact(OpenPaaSDomain domain, OpenPaaSUser user, Optional<UserContact> userContact) {
        return Mono.fromCallable(() -> toAddressBookContact(user, userContact))
            .flatMap(addressBookContact -> retrieveDomainMemberContact(domain, addressBookContact)
                .filter(existing -> shouldUpsert(userContact, addressBookContact, existing))
                .flatMap(ignored -> upsert(domain, user, addressBookContact)));
    }

    private AddressBookContact toAddressBookContact(OpenPaaSUser user, Optional<UserContact> userContact) throws Exception {
        AddressBookContact.Builder builder = AddressBookContact.builder().mail(user.username().asMailAddress());
        userContact.ifPresent(builder::userContact);
        return builder.build();
    }

    private Mono<Optional<AddressBookContact>> retrieveDomainMemberContact(OpenPaaSDomain domain, AddressBookContact contact) {
        return cardDavClient.retrieveContactDomainMembers(domain.id(), contact.vcardUid())
            .map(vcard -> AddressBookContact.parse(vcard).stream().findFirst())
            .defaultIfEmpty(Optional.empty());
    }

    // Without directory details, a card holding only the mail is written once and never overrides a fuller one.
    private boolean shouldUpsert(Optional<UserContact> userContact, AddressBookContact addressBookContact, Optional<AddressBookContact> existing) {
        return existing
            .map(existingContact -> userContact.isPresent() && !addressBookContact.hasSameContentAs(existingContact))
            .orElse(true);
    }

    private Mono<Void> upsert(OpenPaaSDomain domain, OpenPaaSUser user, AddressBookContact contact) {
        return cardDavClient.upsertContactDomainMembers(domain.id(), contact.vcardUid(), contact.toVcardBytes())
            .doOnSuccess(ignored -> LOGGER.info("Upserted user {} in domain member addressbook of domain {}",
                user.username().asString(), domain.domain().asString()));
    }
}
