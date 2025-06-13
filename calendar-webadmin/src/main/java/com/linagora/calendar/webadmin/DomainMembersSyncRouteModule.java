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

package com.linagora.calendar.webadmin;

import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.dto.DTOModuleInjections;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;
import com.linagora.calendar.webadmin.DomainMembersAddressBookRoutes.LdapToDavDomainMembersSyncTaskRegistration;
import com.linagora.calendar.webadmin.service.LdapToDavDomainMembersSyncService;
import com.linagora.calendar.webadmin.task.DomainMembersSyncTaskAdditionalInformationDTO;

public class DomainMembersSyncRouteModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(LdapToDavDomainMembersSyncService.class).in(Scopes.SINGLETON);
    }

    @ProvidesIntoSet
    public TaskFromRequestRegistry.TaskRegistration ldapToDavDomainMembersSyncTaskRegistration(LdapToDavDomainMembersSyncTaskRegistration taskRegistration) {
        return taskRegistration;
    }

    @Named(DTOModuleInjections.WEBADMIN_DTO)
    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> domainMembersSyncTaskAdditionalInformation() {
        return DomainMembersSyncTaskAdditionalInformationDTO.module();
    }

    @ProvidesIntoSet
    public Routes domainMembersSyncRoutes(DomainMembersAddressBookRoutes domainMembersAddressBookRoutes) {
        return domainMembersAddressBookRoutes;
    }
}
