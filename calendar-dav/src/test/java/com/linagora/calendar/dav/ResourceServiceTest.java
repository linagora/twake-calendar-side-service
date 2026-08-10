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

import static com.linagora.calendar.dav.ResourceService.ResourceAdministrator;
import static com.linagora.calendar.dav.ResourceService.ResourceWithAdministration.ResolvedAdministrator;
import static com.linagora.calendar.dav.ResourceService.ONLY_ACTIVE;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.AddSharee;
import com.linagora.calendar.dav.CalDavClient.CalendarSharingUpdate.Share;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSDomainDAO;
import com.linagora.calendar.storage.mongodb.MongoDBOpenPaaSUserDAO;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;

class ResourceServiceTest {
    @RegisterExtension
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private OpenPaaSDomain domain;
    private MongoDBResourceDAO resourceDAO;
    private MongoDBOpenPaaSUserDAO userDAO;
    private CalDavClient calDavClient;
    private ResourceService testee;

    @BeforeEach
    void setUp() throws SSLException {
        domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of(SabreDavProvisioningService.DOMAIN))
            .block();
        MongoDBOpenPaaSDomainDAO domainDAO = new MongoDBOpenPaaSDomainDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB());
        resourceDAO = new MongoDBResourceDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), Clock.systemUTC());
        userDAO = new MongoDBOpenPaaSUserDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), domainDAO);
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        testee = new ResourceService(userDAO, resourceDAO, calDavClient);
    }

    @Test
    void listAdministratorsShouldReturnReadWriteAndAdministrationMailtoUsers() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser readWriteUser = sabreDavExtension.newTestUser(Optional.of("admin_"));
        OpenPaaSUser reader = sabreDavExtension.newTestUser(Optional.of("reader_"));
        OpenPaaSUser davAdmin = sabreDavExtension.newTestUser(Optional.of("dav_admin_"));
        Resource resource = createResource(creator);

        share(resource.id(),
            AddSharee.readWrite(mailto(readWriteUser)),
            AddSharee.read(mailto(reader)),
            AddSharee.administration(mailto(davAdmin)));

        List<ResourceService.ResourceAdministrator> actual = testee.listAdministrators(resource)
            .block();

        assertThat(actual)
            .containsExactlyInAnyOrder(
                new ResourceAdministrator(readWriteUser.username(), DavRight.READ_WRITE),
                new ResourceAdministrator(davAdmin.username(), DavRight.ADMINISTRATION));
    }

    @Test
    void retrieveWithAdministrationShouldKeepAdministratorsAndTheirDavRights() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser readWriteUser = sabreDavExtension.newTestUser(Optional.of("admin_"));
        OpenPaaSUser davAdmin = sabreDavExtension.newTestUser(Optional.of("dav_admin_"));
        Resource resource = createResource(creator);

        share(resource.id(),
            AddSharee.readWrite(mailto(readWriteUser)),
            AddSharee.administration(mailto(davAdmin)));

        ResourceService.ResourceWithAdministration actual = testee.retrieveWithAdministration(resource.id(), ONLY_ACTIVE)
            .block();

        assertThat(actual.administratorsWithRight())
            .containsExactlyInAnyOrder(
                new ResolvedAdministrator(readWriteUser, DavRight.READ_WRITE),
                new ResolvedAdministrator(davAdmin, DavRight.ADMINISTRATION));
        assertThat(actual.administrators())
            .containsExactlyInAnyOrder(readWriteUser, davAdmin);
    }

    @Test
    void listAdminUsersShouldReturnEmptyWhenInviteListIsEmpty() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        Resource resource = createResource(creator);

        List<OpenPaaSUser> actual = testee.listAdminUsers(resource)
            .block();

        assertThat(actual).isEmpty();
    }

    @Test
    void listAdminUsersShouldSkipUnresolvedUsers() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser resolvedUser = sabreDavExtension.newTestUser(Optional.of("resolved_"));
        OpenPaaSUser unresolvedUser = sabreDavExtension.newTestUser(Optional.of("unresolved_"));
        Resource resource = createResource(creator);
        MemoryOpenPaaSUserDAO memoryUserDAO = new MemoryOpenPaaSUserDAO();
        OpenPaaSUser expectedResolvedUser = memoryUserDAO.add(resolvedUser.username()).block();
        ResourceService resolver = new ResourceService(memoryUserDAO, resourceDAO, calDavClient);

        share(resource.id(),
            AddSharee.readWrite(mailto(resolvedUser)),
            AddSharee.readWrite(mailto(unresolvedUser)));

        List<OpenPaaSUser> actual = resolver.listAdminUsers(resource)
            .block();

        assertThat(actual)
            .containsExactly(expectedResolvedUser);
    }

    @Test
    void isAdministratorShouldReturnTrueOnlyForReadWriteMailtoUsers() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        OpenPaaSUser readWriteUser = sabreDavExtension.newTestUser(Optional.of("admin_"));
        OpenPaaSUser reader = sabreDavExtension.newTestUser(Optional.of("reader_"));
        Resource resource = createResource(creator);

        share(resource.id(),
            AddSharee.readWrite(mailto(readWriteUser)),
            AddSharee.read(mailto(reader)));

        assertThat(testee.isAdministrator(resource, readWriteUser.username()).block())
            .isTrue();
        assertThat(testee.isAdministrator(resource, reader.username()).block())
            .isFalse();
    }

    @Test
    void createShouldFailWhenAdministratorDoesNotExist() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        Username missingAdministrator = Username.of("missing_admin@" + domain.domain().asString());
        ResourceInsertRequest request = new ResourceInsertRequest(
            creator.id(),
            "Resource administrator resolver test",
            domain.id(),
            "projector",
            "Resource " + UUID.randomUUID());
        long resourceCountBefore = resourceDAO.findAll().count().block();

        assertThatThrownBy(() -> testee.create(request, List.of(new ResourceAdministrator(
            missingAdministrator, DavRight.ADMINISTRATION))).block())
            .isInstanceOf(ResourceAdministratorNotFoundException.class)
            .hasMessage("Resource administrator '%s' must exist".formatted(missingAdministrator.asString()));

        assertThat(resourceDAO.findAll().count().block())
            .isEqualTo(resourceCountBefore);
    }

    @Test
    void updateAdminsShouldFailWhenAdministratorDoesNotExist() {
        OpenPaaSUser creator = sabreDavExtension.newTestUser(Optional.of("creator_"));
        Resource resource = createResource(creator);
        Username missingAdministrator = Username.of("missing_admin@" + domain.domain().asString());

        assertThatThrownBy(() -> testee.updateAdmins(resource, List.of(missingAdministrator)).block())
            .isInstanceOf(ResourceAdministratorNotFoundException.class)
            .hasMessage("Resource administrator '%s' must exist".formatted(missingAdministrator.asString()));
    }

    private Resource createResource(OpenPaaSUser creator) {
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(
                creator.id(),
                "Resource administrator resolver test",
                domain.id(),
                "projector",
                "Resource " + UUID.randomUUID()))
            .block();
        return resourceDAO.findById(resourceId).block();
    }

    private void share(ResourceId resourceId, AddSharee... sharees) {
        calDavClient.updateCalendarShares(domain.id(),
            CalendarURL.from(resourceId.asOpenPaaSId()),
            new CalendarSharingUpdate(new Share(List.of(sharees), List.of())))
            .block();
    }

    private String mailto(OpenPaaSUser user) {
        return "mailto:" + user.username().asString();
    }
}
