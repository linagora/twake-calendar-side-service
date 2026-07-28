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

import org.apache.james.core.Domain;
import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;

import jakarta.inject.Inject;

public class ResourceProbe implements GuiceProbe {
    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO domainDAO;

    @Inject
    ResourceProbe(ResourceDAO resourceDAO, OpenPaaSDomainDAO domainDAO) {
        this.resourceDAO = resourceDAO;
        this.domainDAO = domainDAO;
    }

    public Resource save(OpenPaaSUser requestUser, String name, String icon) {
        return saveInDomain(requestUser.username().getDomainPart().orElseThrow(), requestUser.id(), name, icon);
    }

    public Resource saveInDomain(Domain domain, OpenPaaSId owner, String name, String icon) {
        OpenPaaSDomain openPaaSDomain = domainDAO.retrieve(domain).block();
        List<ResourceAdministrator> administrators = List.of(new ResourceAdministrator(owner, "user"));
        ResourceInsertRequest insertRequest = new ResourceInsertRequest(administrators, owner,
            name + " description", openPaaSDomain.id(), icon, name);
        return resourceDAO.insert(insertRequest).flatMap(resourceDAO::findById).block();
    }

    public void remove(ResourceId resourceId) {
        resourceDAO.softDelete(resourceId).block();
    }
}
