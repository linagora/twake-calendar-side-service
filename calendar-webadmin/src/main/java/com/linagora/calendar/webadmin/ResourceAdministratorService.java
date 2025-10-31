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

package com.linagora.calendar.webadmin;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class ResourceAdministratorService {
    private record AdminChanges(Set<OpenPaaSId> toAdd, Set<OpenPaaSId> toRemove) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceAdministratorService.class);

    private final CalDavClient calDavClient;
    private final OpenPaaSUserDAO userDAO;

    @Inject
    @Singleton
    public ResourceAdministratorService(CalDavClient calDavClient, OpenPaaSUserDAO userDAO) {
        this.calDavClient = calDavClient;
        this.userDAO = userDAO;
    }

    public Mono<Void> setAdmins(OpenPaaSId domainId, ResourceId resourceId, List<Username> admins) {
        return calDavClient.grantReadWriteRights(domainId, resourceId, admins)
            .doOnError(err -> LOGGER.error("Error granting rights for resource {}", resourceId.value(), err));
    }

    public Mono<Void> revokeAdmins(Resource resource) {
        return resolveAdminUsernames(resource)
            .flatMap(adminUsers -> calDavClient.revokeWriteRights(resource.domain(), resource.id(), adminUsers)
                .doOnError(error -> LOGGER.error("Error revoking write rights for resource {}", resource.id().value(), error)));
    }

    public Mono<List<ResourceAdministrator>> updateAdmins(Resource resource, List<ResourceRoutes.AdministratorDTO> administrators) {
        return resolveValidAdministrators(administrators)
            .map(adminMap -> adminMap.values().stream().toList())
            .flatMap(newAdmins -> applyCalDavPatch(resource, detectUserAdminChanges(resource, newAdmins))
                .thenReturn(newAdmins));
    }

    private Mono<Void> applyCalDavPatch(Resource resource, AdminChanges changes) {
        return Mono.zip(findUsernamesByIds(changes.toAdd()), findUsernamesByIds(changes.toRemove()))
            .flatMap(tuple -> calDavClient.patchReadWriteDelegations(
                resource.domain(), CalendarURL.from(resource.id().asOpenPaaSId()), tuple.getT1(), tuple.getT2()))
            .doOnError(err -> LOGGER.error("Error patching CalDAV delegation for resource {}", resource.id().value(), err));
    }

    private AdminChanges detectUserAdminChanges(Resource currentResource, Collection<ResourceAdministrator> newAdmins) {
        List<ResourceAdministrator> currentAdmins = filterUserAdministrators(currentResource);

        Set<OpenPaaSId> currentIds = currentAdmins.stream()
            .map(ResourceAdministrator::refId)
            .collect(Collectors.toSet());
        Set<OpenPaaSId> newIds = newAdmins.stream()
            .map(ResourceAdministrator::refId)
            .collect(Collectors.toSet());

        Set<OpenPaaSId> toAdd = Sets.difference(newIds, currentIds);
        Set<OpenPaaSId> toRemove = Sets.difference(currentIds, newIds);
        return new AdminChanges(toAdd, toRemove);
    }

    private Mono<Map<Username, ResourceAdministrator>> resolveValidAdministrators(List<ResourceRoutes.AdministratorDTO> dtos) {
        return Flux.fromIterable(dtos)
            .map(dto -> Username.of(dto.email()))
            .flatMap(user -> userDAO.retrieve(user)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("username '%s' must exist".formatted(user.asString())))))
            .collectMap(OpenPaaSUser::username, user -> new ResourceAdministrator(user.id(), "user"));
    }

    private Mono<List<Username>> resolveAdminUsernames(Resource resource) {
        Set<OpenPaaSId> ids = filterUserAdministrators(resource).stream()
            .map(ResourceAdministrator::refId)
            .collect(Collectors.toSet());

        return findUsernamesByIds(ids);
    }

    public List<ResourceAdministrator> filterUserAdministrators(Resource currentResource) {
        return Optional.ofNullable(currentResource.administrators()).orElse(List.of())
            .stream()
            .filter(admin -> Strings.CI.equals(admin.objectType(), "user"))
            .toList();
    }

    private Mono<List<Username>> findUsernamesByIds(Collection<OpenPaaSId> ids) {
        return Flux.fromIterable(ids)
            .flatMap(id -> userDAO.retrieve(id)
                .map(OpenPaaSUser::username)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("User id '" + id + "' does not exist"))), ReactorUtils.LOW_CONCURRENCY)
            .collectList();
    }
}
