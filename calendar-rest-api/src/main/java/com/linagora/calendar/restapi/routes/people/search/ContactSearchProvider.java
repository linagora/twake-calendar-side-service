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

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.api.model.AccountId;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.routes.PeopleSearchRoute;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.james.jmap.contact.EmailAddressContact;
import com.linagora.tmail.james.jmap.contact.EmailAddressContactSearchEngine;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ContactSearchProvider implements PeopleSearchProvider {

    public static final ImmutableSet<PeopleSearchRoute.ObjectType> OBJECT_TYPES = ImmutableSet.of(PeopleSearchRoute.ObjectType.USER, PeopleSearchRoute.ObjectType.CONTACT);


    public record UserLookupResult(PeopleSearchRoute.ObjectType objectType, String id) {

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


    private final RestApiConfiguration configuration;
    private final EmailAddressContactSearchEngine contactSearchEngine;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    public ContactSearchProvider(RestApiConfiguration configuration, EmailAddressContactSearchEngine contactSearchEngine, OpenPaaSUserDAO userDAO) {
        this.configuration = configuration;
        this.contactSearchEngine = contactSearchEngine;
        this.userDAO = userDAO;
    }

    @Override
    public Set<PeopleSearchRoute.ObjectType> supportedTypes() {
        return OBJECT_TYPES;
    }

    @Override
    public Flux<PeopleSearchRoute.ResponseDTO> search(Username username, String query, Set<PeopleSearchRoute.ObjectType> objectTypesFilter, int limit) {
        return Flux.from(contactSearchEngine.autoComplete(AccountId.fromString(username.asString()), query, limit))
            .flatMap(contact -> resolveUserOrContactType(contact, objectTypesFilter)
                .map(lookupResult -> contactToResponseDTO(lookupResult).apply(contact)));
    }

    private Mono<UserLookupResult> resolveUserOrContactType(EmailAddressContact contact, Set<PeopleSearchRoute.ObjectType> objectTypesFilter) {
        if (CollectionUtils.isEmpty(objectTypesFilter) || objectTypesFilter.contains(PeopleSearchRoute.ObjectType.USER)) {
            return resolveUserOrContactType(contact);
        }
        return Mono.just(new UserLookupResult(PeopleSearchRoute.ObjectType.CONTACT, contact.id().toString()));
    }

    private Mono<UserLookupResult> resolveUserOrContactType(EmailAddressContact contact) {
        return userDAO.retrieve(Username.fromMailAddress(contact.fields().address()))
            .map(user -> new UserLookupResult(PeopleSearchRoute.ObjectType.USER, user.id().value()))
            .switchIfEmpty(Mono.just(new UserLookupResult(PeopleSearchRoute.ObjectType.CONTACT, contact.id().toString())));
    }

    private Function<EmailAddressContact, PeopleSearchRoute.ResponseDTO> contactToResponseDTO(UserLookupResult userLookupResult) {
        return contact -> new ContactResponseDTO(userLookupResult.id(),
            contact.fields().address().asString(),
            contact.fields().fullName(),
            configuration.getSelfUrl().toString() + "/api/avatars?email=" + contact.fields().address().asString(),
            userLookupResult.objectType().name().toLowerCase());
    }
}
