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

import org.apache.james.core.Username;
import org.apache.james.webadmin.Routes;

import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.webadmin.task.CalendarArchivalTask;
import com.linagora.calendar.webadmin.task.LdapToDavDomainMembersSyncTask;

public class DomainTasksModule extends AbstractModule {
    @Override
    protected void configure() {
        Multibinder<Routes> routesMultibinder = Multibinder.newSetBinder(binder(), Routes.class);
        routesMultibinder.addBinding().to(DomainTasksRoutes.class);

        Multibinder<TaskBelongsToDomainPredicate> predicates =
            Multibinder.newSetBinder(binder(), TaskBelongsToDomainPredicate.class);

        predicates.addBinding().toInstance(
            (domain, details) -> details.getAdditionalInformation()
                .filter(info -> info instanceof CalendarArchivalTask.Details)
                .map(info -> (CalendarArchivalTask.Details) info)
                .flatMap(CalendarArchivalTask.Details::targetUser)
                .flatMap(Username::getDomainPart)
                .map(domain::equals)
                .orElse(false));

        predicates.addBinding().toInstance(
            (domain, details) -> details.getAdditionalInformation()
                .filter(info -> info instanceof LdapToDavDomainMembersSyncTask.Details)
                .map(info -> (LdapToDavDomainMembersSyncTask.Details) info)
                .flatMap(LdapToDavDomainMembersSyncTask.Details::domain)
                .map(domainString -> domainString.equals(domain.asString()))
                .orElse(false));
    }
}
