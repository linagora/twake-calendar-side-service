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

import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.tasks.TaskFromRequestRegistry;
import org.apache.james.webadmin.tasks.TaskRegistrationKey;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.LdapToDavDomainMembersSyncService;
import com.linagora.calendar.webadmin.task.LdapToDavDomainMembersSyncTask;

import reactor.core.publisher.Mono;
import spark.Request;
import spark.Route;
import spark.Service;

public class DomainMembersAddressBookRoutes implements Routes {

    public static class LdapToDavDomainMembersSyncTaskRegistration extends TaskFromRequestRegistry.TaskRegistration {

        public static final TaskRegistrationKey TASK_REGISTRATION_KEY = TaskRegistrationKey.of("sync");

        @Inject
        public LdapToDavDomainMembersSyncTaskRegistration(LdapToDavDomainMembersSyncService syncService, OpenPaaSDomainDAO openPaaSDomainDAO) {
            super(TASK_REGISTRATION_KEY, request -> taskFromRequest(request, openPaaSDomainDAO, syncService));
        }

        private static Task taskFromRequest(Request request, OpenPaaSDomainDAO openPaaSDomainDAO, LdapToDavDomainMembersSyncService syncService) {
            return extractDomain(request)
                .map(domain -> buildSingleDomainTask(domain, openPaaSDomainDAO, syncService))
                .orElseGet(() -> buildAllDomainsTask(request, openPaaSDomainDAO, syncService));
        }

        private static Optional<Domain> extractDomain(Request request) {
            String domainAsString = request.params(TARGET_DOMAIN_PARAMETER);
            return Optional.ofNullable(StringUtils.trimToNull(domainAsString))
                .map(Domain::of);
        }

        private static Task buildSingleDomainTask(Domain domain,
                                                  OpenPaaSDomainDAO openPaaSDomainDAO,
                                                  LdapToDavDomainMembersSyncService syncService) {
            return openPaaSDomainDAO.retrieve(domain)
                .switchIfEmpty(Mono.defer(() -> Mono.error(ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("domain not found: " + domain.asString())
                    .haltError())))
                .map(openPaaSDomain -> LdapToDavDomainMembersSyncTask.singleDomain(openPaaSDomain, syncService, openPaaSDomainDAO))
                .block();
        }

        private static Task buildAllDomainsTask(Request request,
                                                OpenPaaSDomainDAO openPaaSDomainDAO,
                                                LdapToDavDomainMembersSyncService syncService) {
            ImmutableSet<Domain> ignoredDomains = extractIgnoredDomains(request);
            return LdapToDavDomainMembersSyncTask.allDomains(syncService, openPaaSDomainDAO, ignoredDomains);
        }

        private static ImmutableSet<Domain> extractIgnoredDomains(Request request) {
            return Optional.ofNullable(StringUtils.trimToNull(request.queryParams(IGNORED_DOMAINS_PARAMETER)))
                .map(domains -> Splitter.on(DOMAIN_SEPARATOR)
                    .trimResults()
                    .omitEmptyStrings()
                    .splitToStream(domains)
                    .map(Domain::of)
                    .collect(ImmutableSet.toImmutableSet()))
                .orElse(ImmutableSet.of());
        }
    }

    public static final String BASE_PATH = "/addressbook/domain-members";
    private static final String TARGET_DOMAIN_PARAMETER = "targetDomain";
    private static final String IGNORED_DOMAINS_PARAMETER = "ignoredDomains";
    private static final String SINGLE_DOMAIN_PATH = BASE_PATH + SEPARATOR + ":" + TARGET_DOMAIN_PARAMETER;
    private static final char DOMAIN_SEPARATOR = ',';

    private final JsonTransformer jsonTransformer;
    private final TaskManager taskManager;
    private final Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations;

    @Inject
    public DomainMembersAddressBookRoutes(JsonTransformer jsonTransformer,
                                          TaskManager taskManager,
                                          Set<TaskFromRequestRegistry.TaskRegistration> taskRegistrations) {
        this.jsonTransformer = jsonTransformer;
        this.taskManager = taskManager;
        this.taskRegistrations = taskRegistrations;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        syncDomainMembersContactsRoute()
            .ifPresent(route -> service.post(BASE_PATH, route, jsonTransformer));
        syncDomainMembersContactsRoute()
            .ifPresent(route -> service.post(SINGLE_DOMAIN_PATH, route, jsonTransformer));
    }

    private Optional<Route> syncDomainMembersContactsRoute() {
        return TaskFromRequestRegistry.builder()
            .parameterName("task")
            .registrations(taskRegistrations)
            .buildAsRouteOptional(taskManager);
    }
}
