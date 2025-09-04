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

import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ResourceDAO {
    Mono<ResourceId> insert(ResourceInsertRequest request);

    Flux<Resource> findAll();

    Mono<Resource> findById(ResourceId id);

    Flux<Resource> findByDomain(OpenPaaSId domainId);

    Mono<Resource> update(ResourceId id, ResourceUpdateRequest request);

    // Soft delete (set deleted = true)
    Mono<Void> softDelete(ResourceId id);

    Flux<Resource> search(OpenPaaSId domainId, String keyword, int limit);

    Mono<Boolean> exist(ResourceId resourceId, OpenPaaSId domainId);
}
