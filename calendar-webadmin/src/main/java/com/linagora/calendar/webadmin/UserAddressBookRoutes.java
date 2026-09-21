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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.james.core.Username;
import org.apache.james.task.TaskId;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.DavClientException;
import com.linagora.calendar.dav.importer.ContactToImport;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.service.AddressBookImportService;
import com.linagora.calendar.webadmin.task.AddressBookImportTask;

import spark.HaltException;
import spark.Request;
import spark.Response;
import spark.Service;

/**
 * Administrative management of user address books. Calls are proxied to the Sabre
 * DAV server, impersonating the targeted user.
 */
public class UserAddressBookRoutes implements Routes {

    public static final String BASE_PATH = "/users";

    private static final String USERNAME_PARAM = ":username";
    private static final String ADDRESSBOOK_ID_PARAM = ":addressBookId";
    private static final String ADDRESSBOOKS_PATH = BASE_PATH + SEPARATOR + USERNAME_PARAM + SEPARATOR + "addressbooks";
    private static final String ADDRESSBOOK_PATH = ADDRESSBOOKS_PATH + SEPARATOR + ADDRESSBOOK_ID_PARAM;
    private static final String PUBLIC_RIGHT_PATH = ADDRESSBOOK_PATH + SEPARATOR + "publicRight";
    private static final String INVITEE_PATH = ADDRESSBOOK_PATH + SEPARATOR + "invitee";
    private static final String CONTACT_COUNT_PATH = ADDRESSBOOK_PATH + SEPARATOR + "contactCount";

    private static final String ACTION_PARAMETER = "action";
    private static final String EXPORT_ACTION = "export";
    private static final String IMPORT_ACTION = "import";
    private static final String VCARD_CONTENT_TYPE = "text/vcard; charset=utf-8";
    private static final byte[] NO_CONTACT = new byte[0];

    private static final String FIELD_ID = "id";
    private static final String FIELD_TASK_ID = "taskId";
    private static final String FIELD_COUNT = "count";
    private static final String FIELD_NAME = "dav:name";
    private static final String FIELD_DESCRIPTION = "carddav:description";
    private static final String FIELD_PUBLIC_RIGHT = "public_right";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());

    private enum AddressBookOperation {
        DELETE("delete"),
        UPDATE("update");

        private final String label;

        AddressBookOperation(String label) {
            this.label = label;
        }
    }

    public record AddressBookCreationRequest(@JsonProperty(FIELD_ID) Optional<String> id,
                                             @JsonProperty(value = FIELD_NAME, required = true) String name,
                                             @JsonProperty(FIELD_DESCRIPTION) Optional<String> description) {
        public AddressBookCreationRequest {
            Preconditions.checkArgument(StringUtils.isNotBlank(name), "Field '%s' is required", FIELD_NAME);
        }
    }

    private final OpenPaaSUserDAO userDAO;
    private final CardDavClient cardDavClient;
    private final AddressBookImportService addressBookImportService;
    private final TaskManager taskManager;

    @Inject
    public UserAddressBookRoutes(OpenPaaSUserDAO userDAO, CardDavClient cardDavClient,
                                 AddressBookImportService addressBookImportService, TaskManager taskManager) {
        this.userDAO = userDAO;
        this.cardDavClient = cardDavClient;
        this.addressBookImportService = addressBookImportService;
        this.taskManager = taskManager;
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(ADDRESSBOOKS_PATH, this::listAddressBooks);
        service.get(CONTACT_COUNT_PATH, this::countContacts);
        service.post(ADDRESSBOOKS_PATH, this::createAddressBook);
        service.delete(ADDRESSBOOK_PATH, this::deleteAddressBook);
        service.patch(ADDRESSBOOK_PATH, this::updateAddressBookProperties);
        service.post(ADDRESSBOOK_PATH, this::exportOrImportAddressBook);
        service.post(PUBLIC_RIGHT_PATH, this::updatePublicRight);
        service.post(INVITEE_PATH, this::updateInvitees);
    }

    private String listAddressBooks(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);

        byte[] sabreResponse = wrapDavErrors(() -> cardDavClient
            .listUserAddressBooksAsBytes(user.username(), user.id())
            .block());

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return new String(sabreResponse, StandardCharsets.UTF_8);
    }

    private String countContacts(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);

        long count = wrapDavErrors(() -> cardDavClient.countContacts(user.username(), user.id(), addressBookId)
            .blockOptional()
            .orElseThrow(UserAddressBookRoutes::addressBookNotFound));

        response.status(HttpStatus.OK_200);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_COUNT, count)
            .toString();
    }

    private String createAddressBook(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        AddressBookCreationRequest creationRequest = parseBody(request, AddressBookCreationRequest.class);

        String addressBookId = creationRequest.id()
            .map(StringUtils::trimToNull)
            .orElseGet(() -> UUID.randomUUID().toString());

        wrapDavErrors(() -> cardDavClient.createUserAddressBook(user.username(), user.id(),
            new CardDavClient.NewAddressBook(addressBookId, creationRequest.name(), creationRequest.description().orElse(""))).block());

        response.status(HttpStatus.CREATED_201);
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_ID, addressBookId)
            .toString();
    }

    private String deleteAddressBook(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        AddressBookURL addressBookURL = retrieveWritableAddressBook(request, user, AddressBookOperation.DELETE);

        wrapDavErrors(() -> cardDavClient.deleteUserAddressBook(user.username(), addressBookURL).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updateAddressBookProperties(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        CardDavClient.AddressBookPropertiesUpdate update = parseBody(request, CardDavClient.AddressBookPropertiesUpdate.class);
        AddressBookURL addressBookURL = retrieveWritableAddressBook(request, user, AddressBookOperation.UPDATE);

        wrapDavErrors(() -> cardDavClient.updateAddressBookProperties(user.username(), addressBookURL, update).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String exportOrImportAddressBook(Request request, Response response) {
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
        OpenPaaSUser user = retrieveUser(request);
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, user);

        byte[] vcard = wrapDavErrors(() -> cardDavClient.exportContact(user.username(), addressBookURL)
            .blockOptional()
            .orElse(NO_CONTACT));

        response.status(HttpStatus.OK_200);
        response.type(VCARD_CONTENT_TYPE);
        return new String(vcard, StandardCharsets.UTF_8);
    }

    private String importAddressBook(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, user);
        List<ContactToImport> contacts = parseContacts(request);

        TaskId taskId = taskManager.submit(new AddressBookImportTask(addressBookImportService, user.username(), addressBookURL, contacts));

        response.status(HttpStatus.CREATED_201);
        response.header(HttpHeader.LOCATION.asString(), TasksRoutes.BASE + SEPARATOR + taskId.asString());
        response.type(Constants.JSON_CONTENT_TYPE);
        return OBJECT_MAPPER.createObjectNode()
            .put(FIELD_TASK_ID, taskId.asString())
            .toString();
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

    private String updatePublicRight(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        boolean publish = parsePublicRight(request);
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, user);

        wrapDavErrors(() -> cardDavClient.updateAddressBookPublicRight(user.username(), addressBookURL, publish).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String updateInvitees(Request request, Response response) {
        OpenPaaSUser user = retrieveUser(request);
        List<CardDavClient.AddressBookSharee> sharees = parseSharees(request);
        AddressBookURL addressBookURL = retrieveExistingAddressBook(request, user);

        wrapDavErrors(() -> cardDavClient.updateAddressBookShares(user.username(), addressBookURL, sharees).block());

        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private OpenPaaSUser retrieveUser(Request request) {
        String rawUsername = request.params(USERNAME_PARAM);
        try {
            Username username = Username.of(rawUsername);
            return userDAO.retrieve(username)
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("User does not exist")
                    .haltError());
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid username: %s", rawUsername)
                .cause(e)
                .haltError();
        }
    }

    /**
     * Resolves an address book of the user, rejecting system address books (e.g. {@code contacts})
     * as those cannot be altered on the DAV server.
     */
    private AddressBookURL retrieveWritableAddressBook(Request request, OpenPaaSUser user, AddressBookOperation operation) {
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);

        CardDavClient.AddressBook addressBook = wrapDavErrors(() ->
            cardDavClient.listUserAddressBookIds(user.username(), user.id())
                .filter(book -> book.value().equals(addressBookId))
                .next()
                .blockOptional()
                .orElseThrow(UserAddressBookRoutes::addressBookNotFound));

        if (addressBook.type() == CardDavClient.AddressBookType.SYSTEM) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Cannot %s system address book".formatted(operation.label))
                .haltError();
        }

        return new AddressBookURL(user.id(), addressBookId);
    }

    private AddressBookURL retrieveExistingAddressBook(Request request, OpenPaaSUser user) {
        String addressBookId = request.params(ADDRESSBOOK_ID_PARAM);
        boolean exists = wrapDavErrors(() -> cardDavClient.addressBookExists(user.username(), user.id(), addressBookId).block());
        if (!exists) {
            throw addressBookNotFound();
        }
        return new AddressBookURL(user.id(), addressBookId);
    }

    private static HaltException addressBookNotFound() {
        return ErrorResponder.builder()
            .statusCode(HttpStatus.NOT_FOUND_404)
            .type(ErrorResponder.ErrorType.NOT_FOUND)
            .message("Address book does not exist")
            .haltError();
    }

    private boolean parsePublicRight(Request request) {
        JsonNode body = parseBody(request);
        JsonNode publicRightNode = body.path(FIELD_PUBLIC_RIGHT);
        if (!publicRightNode.isTextual()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Field '%s' is required".formatted(FIELD_PUBLIC_RIGHT))
                .haltError();
        }
        String publicRight = publicRightNode.asText();
        return switch (publicRight) {
            case "" -> false;
            case "{DAV:}read" -> true;
            default -> throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid '%s' value: '%s'. Supported values are: '' and '{DAV:}read'".formatted(FIELD_PUBLIC_RIGHT, publicRight))
                .haltError();
        };
    }

    private List<CardDavClient.AddressBookSharee> parseSharees(Request request) {
        JsonNode body = parseBody(request);
        JsonNode shareesNode = body.path("dav:sharee");
        if (!shareesNode.isArray()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Field 'dav:sharee' must be a JSON array")
                .haltError();
        }
        try {
            return OBJECT_MAPPER.readerForListOf(CardDavClient.AddressBookSharee.class).readValue(shareesNode);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private JsonNode parseBody(Request request) {
        try {
            JsonNode body = OBJECT_MAPPER.readTree(request.bodyAsBytes());
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("Request body must be a JSON object");
            }
            return body;
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private <T> T parseBody(Request request, Class<T> type) {
        try {
            return OBJECT_MAPPER.readValue(request.bodyAsBytes(), type);
        } catch (Exception e) {
            throw invalidBody(e);
        }
    }

    private HaltException invalidBody(Exception e) {
        String detail = Optional.ofNullable(ExceptionUtils.getRootCause(e))
            .map(Throwable::getMessage)
            .orElse(e.getMessage());
        return ErrorResponder.builder()
            .statusCode(HttpStatus.BAD_REQUEST_400)
            .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
            .message("Invalid request body: %s".formatted(detail))
            .cause(e)
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
