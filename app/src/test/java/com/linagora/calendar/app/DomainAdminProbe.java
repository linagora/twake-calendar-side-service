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

import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSId;

public class DomainAdminProbe implements GuiceProbe {

    private final OpenPaaSDomainAdminDAO domainAdminDAO;

    @Inject
    public DomainAdminProbe(OpenPaaSDomainAdminDAO domainAdminDAO) {
        this.domainAdminDAO = domainAdminDAO;
    }

    public void addAdmin(OpenPaaSId domainId, OpenPaaSId userId) {
        domainAdminDAO.addAdmins(domainId, List.of(userId))
            .block();
    }
}
