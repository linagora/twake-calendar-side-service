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
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import org.junit.jupiter.api.Test;

import static com.linagora.calendar.storage.model.Resource.DELETED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ResourceDAOContract {
    ResourceDAO dao();

    @Test
    default void insertShouldAddNewResource() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "Test resource description",
            new OpenPaaSId("domain1"),
            "icon.png",
            "Test Resource",
            Instant.now(),
            Instant.now(),
            "resource"
        );

        ResourceId resourceId = dao().insert(request).block();
        Resource expected = Resource.from(resourceId, request);
        Resource actual = dao().findById(resourceId).block();

        assertThat(actual).isEqualTo(expected);
    }

    @Test
    default void findByIdShouldNotReturnDeletedResource() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "Test resource description",
            new OpenPaaSId("domain1"),
            "icon.png",
            "Test Resource",
            Instant.now(),
            Instant.now(),
            "resource"
        );

        ResourceId resourceId = dao().insert(request).block();

        assertThat(dao().findById(resourceId).blockOptional()).isEmpty();
    }

    @Test
    default void findAllShouldReturnAllResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "Resource1",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain2"),
            "icon2.png",
            "Resource2",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().findAll().collectList().block();

        assertThat(actual).containsExactlyInAnyOrder(Resource.from(id1, req1), Resource.from(id2, req2));
    }

    @Test
    default void findAllShouldNotReturnDeletedResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "Resource1",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain2"),
            "icon2.png",
            "Resource2",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().findAll().collectList().block();

        assertThat(actual).containsOnly(Resource.from(id2, req2));
    }

    @Test
    default void findByDomainShouldReturnResourcesWithCorrespondingDomain() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "Resource1",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain2"),
            "icon2.png",
            "Resource2",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().findByDomain(new OpenPaaSId("domain2")).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id2, req2));
    }

    @Test
    default void findByDomainShouldNotReturnDeletedResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "Resource1",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain1"),
            "icon2.png",
            "Resource2",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().findByDomain(new OpenPaaSId("domain1")).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id2, req2));
    }

    @Test
    default void updateShouldUpdateCurrentResource() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        ResourceUpdateRequest updateRequest = new ResourceUpdateRequest(
            Optional.of("UpdatedName"),
            Optional.of("UpdatedDesc"),
            Optional.of("updatedIcon.png"),
            Optional.of(List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")))
        );
        Resource updated = dao().update(resourceId, updateRequest).block();

        Resource expected = new Resource(
            resourceId,
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "UpdatedDesc",
            new OpenPaaSId("domain1"),
            "updatedIcon.png",
            "UpdatedName",
            updated.creation(),
            updated.updated(),
            "resource"
        );

        assertThat(updated).isEqualTo(expected);
    }

    @Test
    default void softDeleteShouldMarkResourceAsDeleted() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        dao().softDelete(resourceId).block();

        assertThat(dao().findById(resourceId).blockOptional()).isEmpty();
    }

    @Test
    default void searchShouldReturnMatchingResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain1"),
            "icon2.png",
            "BetaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().search(new OpenPaaSId("domain1"), "Alpha", 10).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id1, req1));
    }

    @Test
    default void searchShouldBeCaseInsensitive() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        List<Resource> actual = dao().search(new OpenPaaSId("domain1"), "alphA", 10).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id1, req1));
    }

    @Test
    default void searchShouldNotReturnResourcesWithWrongDomain() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain100"),
            "icon2.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().search(new OpenPaaSId("domain1"), "Alpha", 10).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id1, req1));
    }

    @Test
    default void searchShouldReturnLimitedNumberOfResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain1"),
            "icon2.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().search(new OpenPaaSId("domain1"), "Alpha", 1).collectList().block();

        assertThat(actual).hasSize(1);
    }

    @Test
    default void searchShouldNotReturnDeletedResources() {
        ResourceInsertRequest req1 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc1",
            new OpenPaaSId("domain1"),
            "icon1.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceInsertRequest req2 = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")),
            new OpenPaaSId("creator2"),
            !DELETED,
            "desc2",
            new OpenPaaSId("domain1"),
            "icon2.png",
            "AlphaResource",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId id1 = dao().insert(req1).block();
        ResourceId id2 = dao().insert(req2).block();
        List<Resource> actual = dao().search(new OpenPaaSId("domain1"), "Alpha", 10).collectList().block();

        assertThat(actual).containsOnly(Resource.from(id2, req2));
    }

    @Test
    default void existShouldReturnTrueWhenBothResourceIdAndDomainMatch() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        boolean exists = dao().exist(resourceId, new OpenPaaSId("domain1")).block();

        assertThat(exists).isTrue();
    }

    @Test
    default void existShouldReturnFalseWhenDomainDoesNotMatch() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        boolean notExistsDomain = dao().exist(resourceId, new OpenPaaSId("wrongDomain")).block();

        assertThat(notExistsDomain).isFalse();
    }

    @Test
    default void existShouldReturnFalseWhenResourceIdDoesNotMatch() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            !DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        dao().insert(request).block();
        boolean notExistsId = dao().exist(new ResourceId("random-id"), new OpenPaaSId("domain1")).block();

        assertThat(notExistsId).isFalse();
    }

    @Test
    default void existShouldReturnFalseWhenResourceIsDeleted() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        boolean exists = dao().exist(resourceId, new OpenPaaSId("domain1")).block();

        assertThat(exists).isFalse();
    }

    @Test
    default void updateShouldThrowWhenResourceIdNotFound() {
        ResourceId fakeId = new ResourceId("non-existent-id");
        ResourceUpdateRequest updateRequest = new ResourceUpdateRequest(
            Optional.of("Name"),
            Optional.of("Desc"),
            Optional.of("icon.png"),
            Optional.of(List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")))
        );

        assertThatThrownBy(() -> dao().update(fakeId, updateRequest).block())
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    default void updateShouldThrowWhenResourceIsDeleted() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();
        ResourceUpdateRequest updateRequest = new ResourceUpdateRequest(
            Optional.of("UpdatedName"),
            Optional.of("UpdatedDesc"),
            Optional.of("updatedIcon.png"),
            Optional.of(List.of(new ResourceAdministrator(new OpenPaaSId("admin2"), "user")))
        );

        assertThatThrownBy(() -> dao().update(resourceId, updateRequest).block())
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    default void softDeleteShouldThrowWhenResourceIdNotFound() {
        ResourceId fakeId = new ResourceId("non-existent-id");

        assertThatThrownBy(() -> dao().softDelete(fakeId).block())
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    default void softDeleteShouldThrowWhenResourceIsDeleted() {
        ResourceInsertRequest request = new ResourceInsertRequest(
            List.of(new ResourceAdministrator(new OpenPaaSId("admin1"), "user")),
            new OpenPaaSId("creator1"),
            DELETED,
            "desc",
            new OpenPaaSId("domain1"),
            "icon.png",
            "ResourceName",
            Instant.now(),
            Instant.now(),
            "resource"
        );
        ResourceId resourceId = dao().insert(request).block();

        assertThatThrownBy(() -> dao().softDelete(resourceId).block())
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
