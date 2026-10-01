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

import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;

import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Administrative management of domain address books (e.g. {@code dab}, {@code domain-members}).
 * Calls are proxied to the Sabre DAV server, authenticated with a technical token of the targeted domain.
 */
public class DomainAddressBookRoutes implements Routes {

    public static final String BASE_PATH = "/domains";

    private static final String DOMAIN_PARAM = ":domain";
    private static final String ADDRESSBOOK_ID_PARAM = ":addressBookId";
    private static final String ADDRESSBOOK_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM + SEPARATOR + "addressbooks" + SEPARATOR + ADDRESSBOOK_ID_PARAM;
    private static final String CONTACT_COUNT_PATH = ADDRESSBOOK_PATH + SEPARATOR + "contactCount";

    private static final String FIELD_COUNT = "count";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;

    @Inject
    public DomainAddressBookRoutes(OpenPaaSDomainDAO domainDAO, CardDavClient cardDavClient) {
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(CONTACT_COUNT_PATH, this::countContacts);
    }

    private String countContacts(Request request, Response response) {
        OpenPaaSDomain domain = retrieveDomain(request);
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);

        long count = wrapDavErrors(() -> cardDavClient.countDomainContacts(domain.id(), addressBookId)
            .blockOptional()
            .orElseThrow(DomainAddressBookRoutes::addressBookNotFound));

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_COUNT, count)
            .toString();
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

    private <T> T wrapDavErrors(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (DavClientException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.INTERNAL_SERVER_ERROR_500)
                .type(ErrorResponder.ErrorType.SERVER_ERROR)
                .message("Error while calling the DAV server")
                .cause(e)
                .haltError();
        }
    }
}
