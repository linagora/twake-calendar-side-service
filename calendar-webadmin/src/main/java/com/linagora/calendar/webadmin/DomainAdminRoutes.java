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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.apache.james.webadmin.utils.Responses;
import org.eclipse.jetty.http.HttpStatus;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainAdminDAO;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import spark.Request;
import spark.Response;
import spark.Service;

public class DomainAdminRoutes implements Routes {

    public static final String DOMAINS = "domains";
    private static final String DOMAIN_PARAM = ":domainName";
    private static final String ADMINS = "admins";
    private static final String USER_PARAM = ":userName";

    private static final String DOMAIN_BASE_PATH = DOMAINS + SEPARATOR + DOMAIN_PARAM;
    private static final String ADMINS_BASE_PATH = DOMAIN_BASE_PATH + SEPARATOR + ADMINS;
    private static final String ADMIN_USER_PATH = ADMINS_BASE_PATH + SEPARATOR + USER_PARAM;

    @Override
    public String getBasePath() {
        return DOMAINS;
    }

    private final OpenPaaSDomainDAO domainDAO;
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainAdminDAO domainAdminDAO;
    private final JsonTransformer jsonTransformer;

    @Inject
    public DomainAdminRoutes(OpenPaaSDomainDAO domainDAO,
                             OpenPaaSUserDAO userDAO,
                             OpenPaaSDomainAdminDAO domainAdminDAO,
                             JsonTransformer jsonTransformer) {
        this.domainDAO = domainDAO;
        this.userDAO = userDAO;
        this.domainAdminDAO = domainAdminDAO;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public void define(Service service) {
        service.get(ADMINS_BASE_PATH, this::listDomainAdmins, jsonTransformer);
        service.put(ADMIN_USER_PATH, this::addDomainAdmin, jsonTransformer);
        service.delete(ADMIN_USER_PATH, this::revokeDomainAdmin, jsonTransformer);
    }

    private List<String> listDomainAdmins(Request request, Response response) {
        OpenPaaSDomain openPaaSDomain = asOpenPaaSDomain(request.params(DOMAIN_PARAM));

        return domainAdminDAO.listAdmins(openPaaSDomain.id())
            .flatMap(domainAdmin -> userDAO.retrieve(domainAdmin.userId()))
            .map(openPaaSUser -> openPaaSUser.username().asString())
            .sort(String::compareToIgnoreCase)
            .collectList()
            .block();
    }

    private String addDomainAdmin(Request request, Response response) {
        OpenPaaSDomain openPaaSDomain = asOpenPaaSDomain(request.params(DOMAIN_PARAM));
        OpenPaaSUser userAdmin = asAdminUser(request.params(USER_PARAM));
        domainAdminDAO.addAdmins(openPaaSDomain.id(), List.of(userAdmin.id()))
            .block();
        return Responses.returnNoContent(response);
    }

    private String revokeDomainAdmin(Request request, Response response) {
        OpenPaaSDomain openPaaSDomain = asOpenPaaSDomain(request.params(DOMAIN_PARAM));
        OpenPaaSUser userAdmin = asAdminUser(request.params(USER_PARAM));
        domainAdminDAO.revokeAdmin(openPaaSDomain.id(), userAdmin.id())
            .block();
        return Responses.returnNoContent(response);
    }

    private OpenPaaSUser asAdminUser(String username) {
        Preconditions.checkArgument(StringUtils.isNotBlank(username), "Username must not be blank");
        Username adminUserCandidate = Username.of(username);
        return userDAO.retrieve(adminUserCandidate)
            .blockOptional()
            .orElseThrow(() -> ErrorResponder.builder()
                .statusCode(HttpStatus.NOT_FOUND_404)
                .type(ErrorResponder.ErrorType.NOT_FOUND)
                .message("Admin username not found: %s", adminUserCandidate.asString())
                .haltError());
    }

    private OpenPaaSDomain asOpenPaaSDomain(String domainName) {
        try {
            Domain domain = Domain.of(domainName);
            return domainDAO.retrieve(domain).blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Domain not found: %s", domainName)
                    .haltError());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request for domain creation %s", domainName)
                .cause(e)
                .haltError();
        }
    }
}
