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

package com.linagora.calendar.storage;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Inject;

import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryResourceDAO implements ResourceDAO {
    private final Map<ResourceId, Resource> store = new ConcurrentHashMap<>();
    private final Clock clock;

    @Inject
    public MemoryResourceDAO(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<ResourceId> insert(ResourceInsertRequest request) {
        return Mono.fromCallable(() -> {
            ResourceId newId = new ResourceId(UUID.randomUUID().toString());
            Resource resource = Resource.from(newId, request);
            store.put(newId, resource);
            return newId;
        });
    }

    @Override
    public Flux<Resource> findAll() {
        return Flux.fromIterable(store.values());
    }

    @Override
    public Mono<Resource> findById(ResourceId id) {
        return Mono.justOrEmpty(store.get(id));
    }

    @Override
    public Flux<Resource> findByDomain(OpenPaaSId domainId) {
        return Flux.fromStream(store.values().stream()
            .filter(r -> r.domain().equals(domainId)));
    }

    @Override
    public Mono<Resource> update(ResourceId id, ResourceUpdateRequest request) {
        return Mono.just(Optional.ofNullable(store.get(id)).filter(r -> !r.deleted())
            .map(resource -> {
                Resource updated = resource.update(request, clock.instant());
                store.put(id, updated);
                return updated;
        }).orElseThrow(() -> new ResourceNotFoundException(id)));
    }

    @Override
    public Mono<Void> softDelete(ResourceId id) {
        return Mono.fromRunnable(() ->
            Optional.ofNullable(store.get(id)).filter(r -> !r.deleted())
                .ifPresentOrElse(resource -> store.put(id, resource.markAsDeleted(clock.instant())),
                    () -> {
                        throw new ResourceNotFoundException(id);
                }));
    }

    @Override
    public Flux<Resource> search(OpenPaaSId domainId, String keyword, int limit) {
        return Flux.fromStream(store.values().stream()
            .filter(r -> !r.deleted())
            .filter(r -> r.domain().equals(domainId))
            .filter(r -> r.name().toLowerCase().contains(keyword.trim().toLowerCase()))
            .limit(limit));
    }

    @Override
    public Mono<Boolean> exist(ResourceId resourceId, OpenPaaSId domainId) {
        return Mono.just(Optional.ofNullable(store.get(resourceId))
            .filter(r -> !r.deleted())
            .map(resource -> resource.domain().equals(domainId))
            .orElse(false));
    }
}
