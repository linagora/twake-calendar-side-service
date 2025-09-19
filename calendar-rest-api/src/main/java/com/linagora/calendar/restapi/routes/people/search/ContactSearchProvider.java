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

package com.linagora.calendar.restapi.routes.people.search;

import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;
import org.apache.james.jmap.api.model.AccountId;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ObjectType;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.james.jmap.contact.EmailAddressContact;
import com.linagora.tmail.james.jmap.contact.EmailAddressContactSearchEngine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ContactSearchProvider implements PeopleSearchProvider {

    public static final ImmutableSet<ObjectType> OBJECT_TYPES = ImmutableSet.of(ObjectType.CONTACT);

    record UserLookupResult(ObjectType objectType, String id) {

        public static UserLookupResult contact(String id) {
            return new UserLookupResult(ObjectType.CONTACT, id);
        }

        public static UserLookupResult user(String id) {
            return new UserLookupResult(ObjectType.USER, id);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ContactResponseDTO(@JsonIgnore String id,
                                     @JsonIgnore String emailAddress,
                                     @JsonIgnore String displayName,
                                     @JsonIgnore String photoUrl,
                                     String objectType) implements PeopleSearchRoute.ResponseDTO {

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getObjectType() {
            return objectType;
        }

        @Override
        public List<JsonNode> getEmailAddresses() {
            return buildEmailAddresses(emailAddress, "Work");
        }

        @Override
        public List<JsonNode> getNames() {
            return buildNames(displayName);
        }

        @Override
        public List<JsonNode> getPhotos() {
            return buildPhotos(photoUrl);
        }
    }

    public static URI buildAvatarUrl(URL selfUrl, String email) {
        String base = Strings.CI.removeEnd(selfUrl.toString(), "/");
        return URI.create(base + "/api/avatars?email=" + email);
    }

    private final EmailAddressContactSearchEngine contactSearchEngine;
    private final OpenPaaSUserDAO userDAO;
    private final URL baseAvatarUrl;

    @Inject
    public ContactSearchProvider(@Named("selfUrl") URL baseAvatarUrl,
                                 EmailAddressContactSearchEngine contactSearchEngine,
                                 OpenPaaSUserDAO userDAO) {
        this.contactSearchEngine = contactSearchEngine;
        this.userDAO = userDAO;
        this.baseAvatarUrl = baseAvatarUrl;
    }

    @Override
    public Set<ObjectType> supportedTypes() {
        return OBJECT_TYPES;
    }

    @Override
    public Flux<PeopleSearchRoute.ResponseDTO> search(Username username, String query, Set<ObjectType> objectTypesFilter, int limit) {
        return Flux.from(contactSearchEngine.autoComplete(AccountId.fromString(username.asString()), query, limit))
            .flatMap(contact -> resolveUserOrContactType(contact, objectTypesFilter)
                .map(lookupResult -> toResponseDTO(lookupResult, contact)));
    }

    private Mono<UserLookupResult> resolveUserOrContactType(EmailAddressContact contact, Set<ObjectType> filter) {
        boolean allowUser = filter.isEmpty() || filter.contains(ObjectType.USER);

        if (allowUser) {
            return tryResolveUser(contact)
                .defaultIfEmpty(UserLookupResult.contact(contact.id().toString()));
        }

        return Mono.just(UserLookupResult.contact(contact.id().toString()));
    }

    private Mono<UserLookupResult> tryResolveUser(EmailAddressContact contact) {
        return userDAO.retrieve(Username.fromMailAddress(contact.fields().address()))
            .map(user -> UserLookupResult.user(user.id().value()));
    }

    private PeopleSearchRoute.ResponseDTO toResponseDTO(UserLookupResult lookupResult, EmailAddressContact contact) {
        return new ContactResponseDTO(lookupResult.id(),
            contact.fields().address().asString(),
            contact.fields().fullName(),
            buildAvatarUrl(baseAvatarUrl, contact.fields().address().asString()).toString(),
            lookupResult.objectType().name().toLowerCase());
    }
}
