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

import static com.linagora.calendar.webadmin.CalendarUserTaskRoutes.USER_TASKS;

import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.webadmin.dto.DTOModuleInjections;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.linagora.calendar.webadmin.task.LdapUsersImportTaskAdditionalInformationDTO;

public class LdapUsersImportRouteModule extends AbstractModule {
    @Override
    protected void configure() {
        Multibinder<TaskFromRequestRegistry.TaskRegistration> userTaskRegistrationMultibinder = Multibinder.newSetBinder(binder(),
            TaskFromRequestRegistry.TaskRegistration.class,
            Names.named(USER_TASKS));
        userTaskRegistrationMultibinder.addBinding().to(CalendarUserTaskRoutes.LdapUsersImportRequestToTask.class);
    }

    @Named(DTOModuleInjections.WEBADMIN_DTO)
    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> ldapUsersImportTaskAdditionalInformation() {
        return LdapUsersImportTaskAdditionalInformationDTO.module();
    }
}
