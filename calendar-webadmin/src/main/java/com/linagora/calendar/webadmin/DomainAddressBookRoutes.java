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

import static com.linagora.calendar.webadmin.WebAdminRouteUtils.wrapDavErrors;
import static org.apache.james.webadmin.Constants.SEPARATOR;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;

import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Administrative access to the address books owned by a domain (e.g. {@code dab}, {@code domain-members}).
 * Calls are proxied to the Sabre DAV server using the domain technical token.
 */
public class DomainAddressBookRoutes implements Routes {

    public record ContactCountResponse(@JsonProperty("count") long count) {
    }

    public static final String BASE_PATH = "/domains";

    private static final String DOMAIN_PARAM = ":domain";
    private static final String ADDRESSBOOK_ID_PARAM = ":addressBookId";
    private static final String CONTACT_COUNT_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM + SEPARATOR + "addressbooks"
        + SEPARATOR + ADDRESSBOOK_ID_PARAM + SEPARATOR + "contactCount";

    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;
    private final JsonTransformer jsonTransformer;

    @Inject
    public DomainAddressBookRoutes(OpenPaaSDomainDAO domainDAO, CardDavClient cardDavClient, JsonTransformer jsonTransformer) {
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(CONTACT_COUNT_PATH, this::countContacts, jsonTransformer);
    }

    private ContactCountResponse countContacts(Request request, Response response) {
        OpenPaaSDomain domain = retrieveDomain(request);
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);

        return wrapDavErrors(() -> cardDavClient.countDomainContacts(domain.id(), addressBookId)
            .map(ContactCountResponse::new)
            .blockOptional()
            .orElseThrow(DomainAddressBookRoutes::addressBookNotFound));
    }

    private OpenPaaSDomain retrieveDomain(Request request) {
        String rawDomain = request.params(DOMAIN_PARAM);
        try {
            return domainDAO.retrieve(Domain.of(rawDomain))
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Domain does not exist")
                    .haltError());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s", rawDomain)
                .cause(e)
                .haltError();
        }
    }

    private static HaltException addressBookNotFound() {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Address book does not exist")
            .haltError();
    }
}
