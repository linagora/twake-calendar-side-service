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

package com.linagora.calendar.restapi.routes;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Resolves the resources of a booking link, which are stored as ids of resources belonging to the booking link
 * owner's domain and turned into {@code CUTYPE=RESOURCE} attendees of the created event.
 */
public class BookingLinkResourceResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkResourceResolver.class);

    /**
     * A resource resolved to what an event attendee line needs: its display name and its {@code resourceId@domain}
     * cal-address (the address the DAV server addresses resources by, see {@code CalDavEventRepository#updatePartStat}).
     */
    public record ResolvedResource(String name, MailAddress mailAddress) {
    }

    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO domainDAO;

    @Inject
    public BookingLinkResourceResolver(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
    }

    public Mono<Void> validate(Username owner, List<ResourceId> resources) {
        if (resources.isEmpty()) {
            return Mono.empty();
        }
        return domainDAO.retrieve(owner.getDomainPart().orElseThrow(() ->
                new IllegalArgumentException("Booking link owner has no domain: " + owner.asString())))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException("Unknown domain for booking link owner: " + owner.asString())))
            .flatMapMany(domain -> Flux.fromIterable(resources)
                .concatMap(id -> resourceDAO.exist(id, domain.id())
                    .filter(exists -> exists)
                    .switchIfEmpty(Mono.error(() -> unknownResource(id)))))
            .then();
    }

    /**
     * Resolves the resources that still exist, skipping the others: a resource deleted after the booking link was
     * created must not make the link unbookable. The resource cal-address is {@code resourceId@ownerDomain} - a
     * resource always belongs to its booking link owner's domain (enforced by {@link #validate}).
     */
    public Flux<ResolvedResource> resolveExisting(Username owner, List<ResourceId> resources) {
        Domain domain = owner.getDomainPart().orElseThrow(() ->
            new IllegalStateException("Booking link owner has no domain: " + owner.asString()));
        return resolveResources(resources)
            .map(resource -> new ResolvedResource(resource.name(), toMailAddress(resource.id(), domain)));
    }

    public Flux<Resource> resolveNames(List<ResourceId> resources) {
        return resolveResources(resources);
    }

    private Flux<Resource> resolveResources(List<ResourceId> resources) {
        return Flux.fromIterable(resources)
            .concatMap(id -> resourceDAO.findById(id)
                .filter(resource -> !resource.deleted())
                .switchIfEmpty(Mono.fromRunnable(() ->
                    LOGGER.warn("Skipping resource {}: no such resource anymore", id.value()))));
    }

    private MailAddress toMailAddress(ResourceId resourceId, Domain domain) {
        return Throwing.supplier(() -> new MailAddress(resourceId.value(), domain.asString())).get();
    }

    private IllegalArgumentException unknownResource(ResourceId id) {
        return new IllegalArgumentException("'resources' references a resource that does not exist: " + id.value());
    }
}
