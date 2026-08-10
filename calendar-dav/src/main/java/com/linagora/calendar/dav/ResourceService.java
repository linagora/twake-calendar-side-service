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
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.AddSharee;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.RemoveSharee;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.Share;
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
    public record ResourceAdministrator(Username username, DavRight davRight) {
        public static Optional<ResourceAdministrator> from(CalendarInvite invite) {
            if (!MailtoUri.hasMailtoPrefix(invite.href())) {
                return Optional.empty();
            }
            return invite.access()
                .flatMap(DavRight::fromAccess)
                .filter(right -> right != DavRight.READ)
                .map(right -> new ResourceAdministrator(Username.of(MailtoUri.stripMailtoPrefix(invite.href())), right));
        }

        public static AddSharee asAddSharee(ResourceAdministrator administrator) {
            return switch (administrator.davRight) {
                case READ_WRITE -> AddSharee.readWrite("mailto:" + administrator.username.asString());
                case ADMINISTRATION -> AddSharee.administration("mailto:" + administrator.username.asString());
                default -> throw new IllegalArgumentException("Unsupported resource administrator DAV right: " + administrator.davRight);
            };
        }
    }

    public record ResourceWithAdministration(Resource resource, List<ResolvedAdministrator> administratorsWithRight) {
        public record ResolvedAdministrator(OpenPaaSUser user, DavRight davRight) {
        }

        public List<OpenPaaSUser> administrators() {
            return administratorsWithRight.stream()
                .map(ResolvedAdministrator::user)
                .toList();
        }
    }

    private record AdminChanges(Set<ResourceAdministrator> toAddOrUpdate, Set<Username> toRemove) {
        public static AdminChanges adding(Collection<ResourceAdministrator> administrators) {
            return new AdminChanges(Set.copyOf(administrators), Set.of());
        }

        public static AdminChanges between(Collection<ResourceAdministrator> currentAdministrators,
                                           Collection<ResourceAdministrator> newAdministrators) {
            Set<ResourceAdministrator> currentAdministratorSet = Set.copyOf(currentAdministrators);
            Set<ResourceAdministrator> newAdministratorSet = Set.copyOf(newAdministrators);
            Set<Username> currentUsernames = currentAdministratorSet.stream()
                .map(ResourceAdministrator::username)
                .collect(Collectors.toSet());
            Set<Username> newUsernames = newAdministratorSet.stream()
                .map(ResourceAdministrator::username)
                .collect(Collectors.toSet());
            return new AdminChanges(Set.copyOf(Sets.difference(newAdministratorSet, currentAdministratorSet)),
                Set.copyOf(Sets.difference(currentUsernames, newUsernames)));
        }

        public boolean isEmpty() {
            return toAddOrUpdate.isEmpty() && toRemove.isEmpty();
        }
    }

    public static final boolean ONLY_ACTIVE = true;

    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceService.class);
    private static final Map<String, String> WITH_RIGHTS = Map.of("withRights", "true");

    private final OpenPaaSUserDAO userDAO;
    private final ResourceDAO resourceDAO;
    private final CalDavClient calDavClient;

    @Inject
    public ResourceService(OpenPaaSUserDAO userDAO, ResourceDAO resourceDAO, CalDavClient calDavClient) {
        this.userDAO = userDAO;
        this.resourceDAO = resourceDAO;
        this.calDavClient = calDavClient;
    }

    public Mono<ResourceId> create(ResourceInsertRequest request, List<ResourceAdministrator> admins) {
        return resolveValidAdministrators(admins)
            .flatMap(administrators -> resourceDAO.insert(request)
                .delayUntil(resourceId -> applyCalDavPatch(request.domain(), resourceId, AdminChanges.adding(administrators))));
    }

    public Mono<Void> delete(Resource resource) {
        return fetchAdministratorsFromSabre(resource.domain(), resource.id())
            .map(ResourceAdministrator::username)
            .collectList()
            .flatMap(adminUsers -> calDavClient.revokeWriteRights(resource.domain(), resource.id(), adminUsers)
                .doOnError(error -> LOGGER.error("Error revoking write rights for resource {}", resource.id().value(), error)))
            .then(resourceDAO.softDelete(resource.id()));
    }

    public Mono<Boolean> isAdministrator(Resource resource, Username username) {
        return fetchAdministratorsFromSabre(resource.domain(), resource.id())
            .any(administrator -> administrator.username().equals(username));
    }

    public Mono<List<OpenPaaSUser>> listAdminUsers(Resource resource) {
        return listAdministratorsWithRights(resource)
            .map(ResourceWithAdministration.ResolvedAdministrator::user)
            .collectList();
    }

    public Mono<List<ResourceAdministrator>> listAdministrators(Resource resource) {
        return fetchAdministratorsFromSabre(resource.domain(), resource.id()).collectList();
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
            .flatMap(resource -> listAdministratorsWithRights(resource)
                .collectList()
                .map(administrators -> new ResourceWithAdministration(resource, administrators)));
    }

    public Mono<Resource> retrieve(ResourceId resourceId, OpenPaaSId domainId, boolean includeActive) {
        return retrieve(resourceId, includeActive)
            .filter(resource -> resource.domain().equals(domainId));
    }

    public Mono<Void> update(ResourceId resourceId, ResourceUpdateRequest request) {
        return resourceDAO.update(resourceId, request)
            .then();
    }

    public Mono<Void> updateAdmins(Resource resource, Collection<ResourceAdministrator> administrators) {
        return resolveValidAdministrators(administrators)
            .flatMap(newAdmins -> listAdministrators(resource)
                .map(currentAdmins -> AdminChanges.between(currentAdmins, newAdmins)))
            .flatMap(changes -> applyCalDavPatch(resource.domain(), resource.id(), changes));
    }

    public Mono<Void> updateAdmins(Resource resource, List<Username> administrators) {
        return updateAdmins(resource, administrators.stream()
            .map(username -> new ResourceAdministrator(username, DavRight.READ_WRITE))
            .toList());
    }

    private Mono<Void> applyCalDavPatch(OpenPaaSId domainId, ResourceId resourceId, AdminChanges changes) {
        if (changes.isEmpty()) {
            return Mono.empty();
        }
        List<RemoveSharee> removals = changes.toRemove().stream()
            .map(username -> new RemoveSharee("mailto:" + username.asString()))
            .toList();
        return calDavClient.updateCalendarShares(domainId, CalendarURL.from(resourceId.asOpenPaaSId()),
                new CalendarSharingUpdate(new Share(changes.toAddOrUpdate().stream()
                    .map(ResourceAdministrator::asAddSharee)
                    .toList(), removals)))
            .doOnError(err -> LOGGER.error("Error patching CalDAV delegation for resource {}", resourceId.value(), err));
    }

    private Flux<ResourceAdministrator> fetchAdministratorsFromSabre(OpenPaaSId domainId, ResourceId resourceId) {
        CalendarURL calendarURL = CalendarURL.from(resourceId.asOpenPaaSId());
        return calDavClient.fetchCalendarDetails(domainId, calendarURL, WITH_RIGHTS)
            .flatMapMany(response -> Flux.fromIterable(response.invites()))
            .flatMap(invite -> Mono.justOrEmpty(ResourceAdministrator.from(invite)))
            .distinct(ResourceAdministrator::username);
    }

    private Flux<ResourceWithAdministration.ResolvedAdministrator> listAdministratorsWithRights(Resource resource) {
        return fetchAdministratorsFromSabre(resource.domain(), resource.id())
            .flatMap(administrator -> userDAO.retrieve(administrator.username())
                .map(user -> new ResourceWithAdministration.ResolvedAdministrator(user, administrator.davRight()))
                .switchIfEmpty(Mono.defer(() -> {
                    LOGGER.warn("Ignoring resource administrator '{}' for resource '{}' because user does not exist",
                        administrator.username().asString(), resource.id().value());
                    return Mono.empty();
                })), ReactorUtils.LOW_CONCURRENCY)
            .distinct(administrator -> administrator.user().id());
    }

    private Mono<List<ResourceAdministrator>> resolveValidAdministrators(Collection<ResourceAdministrator> administrators) {
        return Flux.fromIterable(administrators)
            .flatMap(admin -> userDAO.retrieve(admin.username())
                .map(ignored -> admin)
                .switchIfEmpty(Mono.error(() -> new ResourceAdministratorNotFoundException(admin.username()))))
            .distinct(ResourceAdministrator::username)
            .collectList();
    }

}
