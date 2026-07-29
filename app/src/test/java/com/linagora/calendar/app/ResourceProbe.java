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

package com.linagora.calendar.app;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.dav.ResourceService;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

public class ResourceProbe implements GuiceProbe {
    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final OpenPaaSUserDAO userDAO;
    private final ResourceService resourceService;

    @Inject
    ResourceProbe(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO,
                  OpenPaaSUserDAO userDAO, ResourceService resourceService) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.resourceService = resourceService;
    }

    public Resource save(OpenPaaSUser requestUser, String name, String icon) {
        ResourceInsertRequest insertRequest = buildInsertRequest(requestUser, name, icon);

        return resourceDAO.insert(insertRequest)
            .flatMap(resourceDAO::findById)
            .block();
    }

    public Resource save(OpenPaaSUser requestUser, String name, String icon, List<OpenPaaSId> adminIds) {
        Resource resource = save(requestUser, name, icon);
        resourceService.updateAdmins(resource, adminIds.stream()
            .map(userId -> userDAO.retrieve(userId).block().username())
            .toList()).block();
        return resource;
    }

    public Resource saveInDomain(OpenPaaSId domainId, OpenPaaSId owner, String name, String icon) {
        ResourceInsertRequest insertRequest = new ResourceInsertRequest(owner, name + " description", domainId, icon, name);
        return resourceDAO.insert(insertRequest)
            .flatMap(resourceDAO::findById)
            .block();
    }

    public ResourceId saveAndRemove(OpenPaaSUser requestUser, String name, String icon) {
        ResourceInsertRequest insertRequest = buildInsertRequest(requestUser, name, icon);

        return resourceDAO.insert(insertRequest)
            .flatMap(resourceId -> resourceDAO.softDelete(resourceId).thenReturn(resourceId))
            .block();
    }

    public void remove(ResourceId resourceId) {
        resourceDAO.softDelete(resourceId).block();
    }

    private ResourceInsertRequest buildInsertRequest(OpenPaaSUser requestUser, String name, String icon) {
        OpenPaaSDomain domain = domainDAO.retrieve(requestUser.username().getDomainPart().orElseThrow())
            .block();

        return new ResourceInsertRequest(requestUser.id(), name + " description", domain.id(), icon, name);
    }
}
