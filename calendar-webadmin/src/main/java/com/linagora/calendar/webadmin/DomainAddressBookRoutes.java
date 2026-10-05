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

import static com.linagora.calendar.webadmin.WebAdminRouteUtils.createdTaskResponse;
import static com.linagora.calendar.webadmin.WebAdminRouteUtils.invalidBody;
import static com.linagora.calendar.webadmin.WebAdminRouteUtils.wrapDavErrors;
import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.importer.ContactToImport;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.webadmin.service.AddressBookImportService;
import com.linagora.calendar.webadmin.service.DomainAddressBookClearService;
import com.linagora.calendar.webadmin.task.DomainAddressBookClearTask;
import com.linagora.calendar.webadmin.task.DomainAddressBookImportTask;

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
    private static final String ADDRESSBOOK_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM + SEPARATOR + "addressbooks"
        + SEPARATOR + ADDRESSBOOK_ID_PARAM;
    private static final String CONTACT_COUNT_PATH = ADDRESSBOOK_PATH + SEPARATOR + "contactCount";
    private static final String CONTACTS_PATH = ADDRESSBOOK_PATH + SEPARATOR + "contacts";

    private static final String ACTION_PARAMETER = "action";
    private static final String EXPORT_ACTION = "export";
    private static final String IMPORT_ACTION = "import";
    private static final String SOURCE_DOMAIN_PARAMETER = "sourceDomain";
    private static final String VCARD_CONTENT_TYPE = "text/vcard; charset=utf-8";
    private static final byte[] NO_CONTACT = new byte[0];

    private final OpenPaaSDomainDAO domainDAO;
    private final CardDavClient cardDavClient;
    private final AddressBookImportService addressBookImportService;
    private final DomainAddressBookClearService addressBookClearService;
    private final TaskManager taskManager;
    private final JsonTransformer jsonTransformer;

    @Inject
    public DomainAddressBookRoutes(OpenPaaSDomainDAO domainDAO, CardDavClient cardDavClient,
                                   AddressBookImportService addressBookImportService,
                                   DomainAddressBookClearService addressBookClearService, TaskManager taskManager,
                                   JsonTransformer jsonTransformer) {
        this.domainDAO = domainDAO;
        this.cardDavClient = cardDavClient;
        this.addressBookImportService = addressBookImportService;
        this.addressBookClearService = addressBookClearService;
        this.taskManager = taskManager;
        this.jsonTransformer = jsonTransformer;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(CONTACT_COUNT_PATH, this::countContacts, jsonTransformer);
        service.post(ADDRESSBOOK_PATH, this::exportOrImportAddressBook);
        service.delete(CONTACTS_PATH, this::clearContacts);
    }

    private ContactCountResponse countContacts(Request request, Response response) {
        OpenPaaSDomain domain = retrieveDomain(request);
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);

        return wrapDavErrors(() -> cardDavClient.countDomainContacts(domain.id(), addressBookId)
            .map(ContactCountResponse::new)
            .blockOptional()
            .orElseThrow(DomainAddressBookRoutes::addressBookNotFound));
    }

    private String exportOrImportAddressBook(Request request, Response response) throws Exception {
        String action = StringUtils.trimToEmpty(request.queryParams(ACTION_PARAMETER));

        return switch (action.toLowerCase(Locale.US)) {
            case EXPORT_ACTION -> exportAddressBook(request, response);
            case IMPORT_ACTION -> importAddressBook(request, response);
            default -> throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' query parameter: '%s'. Supported values are: '%s', '%s'"
                    .formatted(ACTION_PARAMETER, action, EXPORT_ACTION, IMPORT_ACTION))
                .haltError();
        };
    }

    private String exportAddressBook(Request request, Response response) {
        OpenPaaSDomain domain = retrieveDomain(request);
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, domain);

        byte[] vcard = wrapDavErrors(() -> cardDavClient.exportDomainAddressBook(domain.id(), addressBookURL)
            .blockOptional()
            .orElse(NO_CONTACT));

        response.status(HttpStatus.OK_200);
        response.type(VCARD_CONTENT_TYPE);
        return new String(vcard, StandardCharsets.UTF_8);
    }

    private String importAddressBook(Request request, Response response) throws Exception {
        OpenPaaSDomain domain = retrieveDomain(request);
        AddressBookURL addressBookURL = retrieveImportableAddressBook(request, domain);
        List<ContactToImport> contacts = parseContacts(request);

        TaskId taskId = taskManager.submit(new DomainAddressBookImportTask(addressBookImportService, domain, addressBookURL, contacts));
        return createdTaskResponse(response, taskId);
    }

    private String clearContacts(Request request, Response response) {
        OpenPaaSDomain domain = retrieveDomain(request);
        Optional<Domain> sourceDomain = parseSourceDomain(request);
        AddressBookURL addressBookURL = retrieveWritableAddressBook(request, domain, "clear");

        TaskId taskId = taskManager.submit(new DomainAddressBookClearTask(addressBookClearService, domain, addressBookURL, sourceDomain));
        return createdTaskResponse(response, taskId);
    }

    private AddressBookURL retrieveImportableAddressBook(Request request, OpenPaaSDomain domain) {
        return retrieveWritableAddressBook(request, domain, "import into");
    }

    /**
     * The {@code domain-members} address book mirrors the users of the domain: it is fed by the LDAP
     * synchronization hence rejects direct writes.
     */
    private AddressBookURL retrieveWritableAddressBook(Request request, OpenPaaSDomain domain, String operation) {
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, domain);
        if (CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID.equals(addressBookURL.addressBookId())) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Cannot %s the '%s' address book".formatted(operation, CardDavClient.DOMAIN_MEMBERS_ADDRESS_BOOK_ID))
                .haltError();
        }
        return addressBookURL;
    }

    private Optional<Domain> parseSourceDomain(Request request) {
        Optional<String> rawSourceDomain = Optional.ofNullable(request.queryParams(SOURCE_DOMAIN_PARAMETER));
        try {
            return rawSourceDomain.map(Domain::of);
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' query parameter: %s".formatted(SOURCE_DOMAIN_PARAMETER, rawSourceDomain.orElseThrow()))
                .cause(e)
                .haltError();
        }
    }

    private AddressBookURL retrieveExistingAddressBook(Request request, OpenPaaSDomain domain) {
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);
        boolean exists = wrapDavErrors(() -> cardDavClient.domainAddressBookExists(domain.id(), addressBookId).block());
        if (!exists) {
            throw addressBookNotFound();
        }
        return new AddressBookURL(domain.id(), addressBookId);
    }

    private List<ContactToImport> parseContacts(Request request) {
        List<ContactToImport> contacts;
        try {
            contacts = ContactToImport.parse(request.bodyAsBytes());
        } catch (Exception e) {
            throw invalidBody(e);
        }
        if (contacts.isEmpty()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request body: no contact to import")
                .haltError();
        }
        return contacts;
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
