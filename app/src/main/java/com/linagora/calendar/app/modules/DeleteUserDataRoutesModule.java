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

package com.linagora.calendar.app.modules;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.dto.DTOModuleInjections;
import org.apache.james.webadmin.routes.DeleteUserDataRoutes;
import org.apache.james.webadmin.service.DeleteUserDataTask;
import org.apache.james.webadmin.service.DeleteUserDataTaskAdditionalInformationDTO;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;
import com.google.inject.name.Named;
import com.linagora.calendar.webadmin.TaskBelongsToDomainPredicate;

public class DeleteUserDataRoutesModule extends AbstractModule {
    @Override
    protected void configure() {
        Multibinder<Routes> routesMultibinder = Multibinder.newSetBinder(binder(), Routes.class);
        routesMultibinder.addBinding().to(DeleteUserDataRoutes.class);

        Multibinder<TaskBelongsToDomainPredicate> predicates =
            Multibinder.newSetBinder(binder(), TaskBelongsToDomainPredicate.class);
        predicates.addBinding().toInstance(
            (domain, details) -> details.getAdditionalInformation()
                .filter(info -> info instanceof DeleteUserDataTask.AdditionalInformation)
                .map(info -> (DeleteUserDataTask.AdditionalInformation) info)
                .map(info -> userBelongsToDomain(domain, info.getUsername()))
                .orElse(false));
    }

    private static boolean userBelongsToDomain(Domain domain, Username username) {
        return username.getDomainPart()
            .map(domain::equals)
            .orElse(false);
    }

    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> deleteUserDataTaskAdditionalInformationDTO() {
        return DeleteUserDataTaskAdditionalInformationDTO.module();
    }

    @Named(DTOModuleInjections.WEBADMIN_DTO)
    @ProvidesIntoSet
    public AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO> webAdminDeleteUserDataTaskAdditionalInformationDTO() {
        return DeleteUserDataTaskAdditionalInformationDTO.module();
    }
}
