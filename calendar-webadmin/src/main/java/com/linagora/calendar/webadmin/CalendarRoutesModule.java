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
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.dto.DTOModuleInjections;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;

import com.google.inject.AbstractModule;
import com.google.inject.Scopes;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;
import com.linagora.calendar.webadmin.task.AlarmScheduleTaskAdditionalInformationDTO;
import com.linagora.calendar.webadmin.task.CalendarEventsReindexTaskAdditionalInformationDTO;

public class CalendarRoutesModule extends AbstractModule {
    @Override
    protected void configure() {
        Multibinder<Routes> routesMultibinder = Multibinder.newSetBinder(binder(), Routes.class);
        routesMultibinder.addBinding().to(CalendarUserRoutes.class);
        routesMultibinder.addBinding().to(CalendarChannelLogoutRoutes.class);
        routesMultibinder.addBinding().to(CalendarRoutes.class);

        bind(MemoryTaskManager.class).in(Scopes.SINGLETON);
        bind(TaskManager.class).to(MemoryTaskManager.class);

        Multibinder<TaskFromRequestRegistry.TaskRegistration> taskRegistrationMultibinder = Multibinder.newSetBinder(binder(), TaskFromRequestRegistry.TaskRegistration.class);
        taskRegistrationMultibinder.addBinding().to(CalendarRoutes.CalendarEventsReindexRequestToTask.class);
        taskRegistrationMultibinder.addBinding().to(CalendarRoutes.AlarmScheduleRequestToTask.class);
    }

    @Named(DTOModuleInjections.WEBADMIN_DTO)
    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> calendarEventsReindexTaskAdditionalInformation() {
        return CalendarEventsReindexTaskAdditionalInformationDTO.module();
    }

    @Named(DTOModuleInjections.WEBADMIN_DTO)
    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> alarmScheduleTaskAdditionalInformation() {
        return AlarmScheduleTaskAdditionalInformationDTO.module();
    }
}
