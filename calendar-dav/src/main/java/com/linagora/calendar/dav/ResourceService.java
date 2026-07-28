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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.linagora.calendar.dav.dto.CalendarDetailsResponse.CalendarInvite;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MailtoUri;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceUpdateRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Singleton
public class ResourceService {
    public record ResourceWithAdministration(Resource resource, List<OpenPaaSUser> administrators) {
    }

    private record AdminChanges(Set<OpenPaaSId> toAdd, Set<OpenPaaSId> toRemove) {
    }

    public static final boolean ONLY_ACTIVE = true;

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceService.class);
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");
    private static final int READ_WRITE_ACCESS = 3;

    private final OpenPaaSUserDAO userDAO;
    private final ResourceDAO resourceDAO;
    private final CalDavClient calDavClient;

    @Inject
    public ResourceService(OpenPaaSUserDAO userDAO, ResourceDAO resourceDAO, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.resourceDAO = resourceDAO;
        this.calDavClient = calDavClient;
    }

    public Mono<ResourceId> create(ResourceInsertRequest request, Collection<Username> admins) {
        return resolveValidAdministrators(admins)
            .map(administrators -> administrators.stream()
                .map(OpenPaaSUser::username)
                .toList())
            .flatMap(adminUsernames -> resourceDAO.insert(request)
                .flatMap(resourceId -> calDavClient.grantReadWriteRights(request.domain(), resourceId, adminUsernames)
                    .doOnError(err -> LOGGER.error("Error granting rights for resource {}", resourceId.value(), err))
                    .thenReturn(resourceId)));
    }

    public Mono<Void> delete(Resource resource) {
        return resolveAdminUsernames(resource.domain(), resource.id())
            .collectList()
            .flatMap(adminUsers -> calDavClient.revokeWriteRights(resource.domain(), resource.id(), adminUsers)
                .doOnError(error -> LOGGER.error("Error revoking write rights for resource {}", resource.id().value(), error)))
            .then(resourceDAO.softDelete(resource.id()));
    }

    public Mono<Boolean> isAdministrator(Resource resource, Username username) {
        return resolveAdminUsers(resource.domain(), resource.id())
            .filter(user -> user.username().equals(username))
            .hasElements();
    }


    public Mono<List<OpenPaaSUser>> listAdminUsers(Resource resource) {
        return resolveAdminUsers(resource.domain(), resource.id())
            .collectList();
    }

    public Flux<Resource> listByDomain(OpenPaaSId domainId) {
        return resourceDAO.findByDomain(domainId);
    }

    public Mono<Resource> retrieve(ResourceId resourceId, boolean includeActive) {
        return resourceDAO.findById(resourceId)
            .filter(resource -> !includeActive || !resource.deleted());
    }

    public Mono<ResourceWithAdministration> retrieveWithAdministration(ResourceId resourceId, boolean includeActive) {
        return retrieve(resourceId, includeActive)
            .flatMap(resource -> listAdminUsers(resource)
                .map(administrators -> new ResourceWithAdministration(resource, administrators)));
    }

    public Mono<ResourceWithAdministration> retrieveWithAdministration(ResourceId resourceId, OpenPaaSId domainId, boolean includeActive) {
        return retrieveWithAdministration(resourceId, includeActive)
            .filter(resourceWithAdministration -> resourceWithAdministration.resource().domain().equals(domainId));
    }

    public Mono<Resource> retrieve(ResourceId resourceId, OpenPaaSId domainId, boolean includeActive) {
        return retrieve(resourceId, includeActive)
            .filter(resource -> resource.domain().equals(domainId));
    }

    public Mono<Void> update(ResourceId resourceId, ResourceUpdateRequest request) {
        return resourceDAO.update(resourceId, request)
            .then();
    }

    public Mono<Void> updateAdmins(Resource resource, Collection<Username> administrators) {
        return resolveValidAdministrators(administrators)
            .flatMap(newAdmins -> detectUserAdminChanges(resource, newAdmins)
                .flatMap(changes -> applyCalDavPatch(resource, changes))
                .then());
    }

    private Mono<Void> applyCalDavPatch(Resource resource, AdminChanges changes) {
        return Mono.zip(findUsernamesByIds(changes.toAdd()), findUsernamesByIds(changes.toRemove()))
            .flatMap(tuple -> calDavClient.patchReadWriteDelegations(
                resource.domain(), CalendarURL.from(resource.id().asOpenPaaSId()), tuple.getT1(), tuple.getT2()))
            .doOnError(err -> LOGGER.error("Error patching CalDAV delegation for resource {}", resource.id().value(), err));
    }

    private Mono<AdminChanges> detectUserAdminChanges(Resource currentResource, Collection<OpenPaaSUser> newAdmins) {
        Mono<Set<OpenPaaSId>> currentIdsMono = listAdminUsers(currentResource)
            .map(adminUsers -> adminUsers.stream().map(OpenPaaSUser::id)
                .collect(Collectors.toSet()));
        Set<OpenPaaSId> newIds = newAdmins.stream()
            .map(OpenPaaSUser::id)
            .collect(Collectors.toSet());

        return currentIdsMono.map(currentIds -> {
            Set<OpenPaaSId> toAdd = Sets.difference(newIds, currentIds);
            Set<OpenPaaSId> toRemove = Sets.difference(currentIds, newIds);
            return new AdminChanges(toAdd, toRemove);
        });
    }

    private Mono<List<Username>> findUsernamesByIds(Collection<OpenPaaSId> ids) {
        return Flux.fromIterable(ids)
            .flatMap(id -> userDAO.retrieve(id)
                .map(OpenPaaSUser::username)
                .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("User id '" + id + "' does not exist"))), ReactorUtils.LOW_CONCURRENCY)
            .collectList();
    }

    private boolean isReadWriteMailtoInvite(CalendarInvite invite) {
        return MailtoUri.hasMailtoPrefix(invite.href())
            && invite.access().filter(access -> access == READ_WRITE_ACCESS).isPresent();
    }

    private Flux<OpenPaaSUser> resolveAdminUsers(OpenPaaSId domainId, ResourceId resourceId) {
        return resolveAdminUsernames(domainId, resourceId)
            .flatMap(username -> userDAO.retrieve(username)
                .switchIfEmpty(Mono.defer(() -> {
                    LOGGER.warn("Ignoring resource administrator '{}' for resource '{}' because user does not exist",
                        username.asString(), resourceId.value());
                    return Mono.empty();
                })), ReactorUtils.LOW_CONCURRENCY)
            .distinct(OpenPaaSUser::id);
    }

    private Flux<Username> resolveAdminUsernames(OpenPaaSId domainId, ResourceId resourceId) {
        CalendarURL calendarURL = CalendarURL.from(resourceId.asOpenPaaSId());
        return calDavClient.fetchCalendarDetails(domainId, calendarURL, WITH_RIGHTS)
            .flatMapMany(response -> Flux.fromIterable(response.invites()))
            .filter(this::isReadWriteMailtoInvite)
            .map(invite -> Username.of(MailtoUri.stripMailtoPrefix(invite.href())))
            .distinct();
    }

    private Mono<List<OpenPaaSUser>> resolveValidAdministrators(Collection<Username> administrators) {
        return Flux.fromIterable(administrators)
            .flatMap(username -> userDAO.retrieve(username)
                .switchIfEmpty(Mono.error(() -> new ResourceAdministratorNotFoundException(username))))
            .collectMap(OpenPaaSUser::username)
            .map(adminMap -> adminMap.values().stream().toList());
    }
}
