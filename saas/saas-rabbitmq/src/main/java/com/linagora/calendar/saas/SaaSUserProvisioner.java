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

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
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

    @Inject
    public SaaSUserProvisioner(OpenPaaSUserDAO userDAO,
                               OpenPaaSDomainDAO domainDAO,
                               CardDavClient cardDavClient,
                               DomainSettingsResolver domainSettingsResolver,
                               UserNameResolver userNameResolver) {
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
        this.domainSettingsResolver = domainSettingsResolver;
        this.userNameResolver = userNameResolver;
    }

    public Mono<OpenPaaSUser> provisionUser(Username username) {
        return registerUser(username)
            .flatMap(user -> addUserToDomainAddressBook(user).thenReturn(user));
    }

    private Mono<OpenPaaSUser> registerUser(Username username) {
        return userDAO.retrieve(username)
            .flatMap(this::reimportDisplayName)
            .switchIfEmpty(userNameResolver.resolve(username)
                .flatMap(names -> userDAO.add(username, names))
                .doOnSuccess(created -> LOGGER.info("Registered user {} from SaaS subscription", username.asString())));
    }

    /**
     * Reimports the display name (first and last name) of an already registered user from the
     * user name resolver (typically LDAP), so a rename in the source directory is reflected on
     * the calendar side upon the next {@code settings} message. See issue #929.
     */
    public Mono<OpenPaaSUser> reimportDisplayName(OpenPaaSUser user) {
        return userNameResolver.resolve(user.username())
            .flatMap(maybeNames -> maybeNames
                .filter(names -> displayNameChanged(user, names))
                .map(names -> userDAO.update(user.id(), user.username(), names.firstname(), names.lastname())
                    .doOnSuccess(ignored -> LOGGER.info("Updated display name of user {}", user.username().asString()))
                    .thenReturn(new OpenPaaSUser(user.username(), user.id(), names.firstname(), names.lastname())))
                .orElseGet(() -> Mono.just(user)));
    }

    private boolean displayNameChanged(OpenPaaSUser user, UserNames names) {
        return !Objects.equals(StringUtils.defaultString(user.firstname()), StringUtils.defaultString(names.firstname()))
            || !Objects.equals(StringUtils.defaultString(user.lastname()), StringUtils.defaultString(names.lastname()));
    }

    private Mono<Void> addUserToDomainAddressBook(OpenPaaSUser user) {
        return Mono.justOrEmpty(user.username().getDomainPart())
            .flatMap(domain -> domainSettingsResolver.resolveUserSearchMode(domain)
                .filter(mode -> mode != UserSearchMode.DISABLED)
                .flatMap(ignored -> domainDAO.retrieve(domain))
                .flatMap(openPaaSDomain -> upsertUserContact(openPaaSDomain, user)));
    }

    private Mono<Void> upsertUserContact(OpenPaaSDomain domain, OpenPaaSUser user) {
        return Mono.fromSupplier(Throwing.supplier(() -> AddressBookContact.builder().mail(user.username().asMailAddress()).build()))
            .flatMap(contact -> cardDavClient.upsertContactDomainMembers(domain.id(), contact.vcardUid(), contact.toVcardBytes()))
            .doOnSuccess(ignored -> LOGGER.info("Added user {} to domain member addressbook of domain {}",
                user.username().asString(), domain.domain().asString()));
    }
}
