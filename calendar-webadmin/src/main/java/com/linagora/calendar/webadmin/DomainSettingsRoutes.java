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

import static com.linagora.calendar.storage.configuration.resolver.BusinessHoursSettingReader.OBJECT_MAPPER_DEFAULT;
import static org.apache.james.webadmin.Constants.SEPARATOR;

import java.util.Optional;

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.util.ValuePatch;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.DefaultCalendarPublicVisibility;
import com.linagora.calendar.storage.DomainSettings;
import com.linagora.calendar.storage.DomainSettingsDAO;
import com.linagora.calendar.storage.DomainSettingsPatch;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.UserSearchMode;

import spark.Request;
import spark.Response;
import spark.Service;

public class DomainSettingsRoutes implements Routes {

    public static final String BASE_PATH = "domains";
    private static final String DOMAIN_PARAM = ":domain";
    private static final String SETTINGS = "settings";
    private static final String DOMAIN_SETTINGS_PATH = BASE_PATH + SEPARATOR + DOMAIN_PARAM + SEPARATOR + SETTINGS;

    private static final String FIELD_USER_SEARCH_MODE = "userSearchMode";
    private static final String FIELD_RESOURCE_SEARCH_ENABLED = "resourceSearchEnabled";
    private static final String FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY = "defaultCalendarPublicVisibility";
    private static final String FIELD_CALENDAR_PUBLIC_VISIBILITY_SETTING_ENABLED = "calendarPublicVisibilitySettingEnabled";

    public record DomainSettingsPutRequest(@JsonProperty(value = "userSearchMode", required = true) @Nullable String userSearchMode,
                                           @JsonProperty(value = "resourceSearchEnabled", required = true) @Nullable Boolean resourceSearchEnabled,
                                           @JsonProperty(value = "defaultCalendarPublicVisibility", required = true) @Nullable String defaultCalendarPublicVisibility,
                                           @JsonProperty(value = "calendarPublicVisibilitySettingEnabled", required = true) @Nullable Boolean calendarPublicVisibilitySettingEnabled) {

        DomainSettings toDomainSettings() {
            DomainSettings.Builder builder = DomainSettings.builder();
            Optional.ofNullable(userSearchMode).map(UserSearchMode::deserialize).ifPresent(builder::userSearchMode);
            Optional.ofNullable(resourceSearchEnabled).ifPresent(builder::resourceSearchEnabled);
            Optional.ofNullable(defaultCalendarPublicVisibility)
                .map(DefaultCalendarPublicVisibility::deserialize)
                .ifPresent(builder::defaultCalendarPublicVisibility);
            Optional.ofNullable(calendarPublicVisibilitySettingEnabled).ifPresent(builder::calendarPublicVisibilitySettingEnabled);
            return builder.build();
        }
    }

    public record DomainSettingsPatchRequest(@JsonProperty(FIELD_USER_SEARCH_MODE) Optional<String> userSearchMode,
                                             @JsonProperty(FIELD_RESOURCE_SEARCH_ENABLED) Optional<Boolean> resourceSearchEnabled,
                                             @JsonProperty(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY) Optional<String> defaultCalendarPublicVisibility,
                                             @JsonProperty(FIELD_CALENDAR_PUBLIC_VISIBILITY_SETTING_ENABLED) Optional<Boolean> calendarPublicVisibilitySettingEnabled) {}

    public record DomainSettingsResponse(@JsonProperty("userSearchMode") @Nullable String userSearchMode,
                                         @JsonProperty("resourceSearchEnabled") @Nullable Boolean resourceSearchEnabled,
                                         @JsonProperty("defaultCalendarPublicVisibility") @Nullable String defaultCalendarPublicVisibility,
                                         @JsonProperty("calendarPublicVisibilitySettingEnabled") @Nullable Boolean calendarPublicVisibilitySettingEnabled,
                                         @JsonProperty("resolved") ResolvedSettings resolved) {

        public record ResolvedSettings(@JsonProperty("userSearchMode") String userSearchMode,
                                       @JsonProperty("resourceSearchEnabled") boolean resourceSearchEnabled,
                                       @JsonProperty("defaultCalendarPublicVisibility") String defaultCalendarPublicVisibility,
                                       @JsonProperty("calendarPublicVisibilitySettingEnabled") boolean calendarPublicVisibilitySettingEnabled) {}

        static DomainSettingsResponse of(DomainSettings stored, DomainSettings resolved) {
            return new DomainSettingsResponse(
                stored.userSearchMode().map(UserSearchMode::serialize).orElse(null),
                stored.resourceSearchEnabled().orElse(null),
                stored.defaultCalendarPublicVisibility().map(DefaultCalendarPublicVisibility::serialize).orElse(null),
                stored.calendarPublicVisibilitySettingEnabled().orElse(null),
                new ResolvedSettings(
                    resolved.userSearchMode().orElse(DomainSettings.DEFAULT_USER_SEARCH_MODE).serialize(),
                    resolved.resourceSearchEnabled().orElse(DomainSettings.DEFAULT_RESOURCE_SEARCH_ENABLED),
                    resolved.defaultCalendarPublicVisibility().orElse(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY).serialize(),
                    resolved.calendarPublicVisibilitySettingEnabled().orElse(DomainSettings.DEFAULT_CALENDAR_PUBLIC_VISIBILITY_SETTING_ENABLED)));
        }
    }

    private final DomainSettingsDAO domainSettingsDAO;
    private final DomainSettingsResolver domainSettingsResolver;
    private final OpenPaaSDomainDAO domainDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<DomainSettingsPutRequest> jsonExtractor;

    @Inject
    public DomainSettingsRoutes(DomainSettingsDAO domainSettingsDAO,
                                DomainSettingsResolver domainSettingsResolver,
                                OpenPaaSDomainDAO domainDAO,
                                JsonTransformer jsonTransformer) {
        this.domainSettingsDAO = domainSettingsDAO;
        this.domainSettingsResolver = domainSettingsResolver;
        this.domainDAO = domainDAO;
        this.jsonTransformer = jsonTransformer;
        this.jsonExtractor = new JsonExtractor<>(DomainSettingsPutRequest.class);
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(DOMAIN_SETTINGS_PATH, this::getDomainSettings, jsonTransformer);
        service.put(DOMAIN_SETTINGS_PATH, this::putDomainSettings);
        service.patch(DOMAIN_SETTINGS_PATH, this::patchDomainSettings);
    }

    private DomainSettingsResponse getDomainSettings(Request request, Response response) {
        Domain domain = asDomain(request);
        return domainSettingsDAO.retrieve(domain)
            .defaultIfEmpty(DomainSettings.DEFAULT_DOMAIN_SETTINGS)
            .flatMap(stored -> domainSettingsResolver.resolve(domain)
                .map(resolved -> DomainSettingsResponse.of(stored, resolved)))
            .block();
    }

    private String putDomainSettings(Request request, Response response) throws JsonExtractException {
        Domain domain = asDomain(request);
        DomainSettingsPutRequest requestDTO = jsonExtractor.parse(request.body());
        DomainSettings settings = requestDTO.toDomainSettings();
        domainSettingsDAO.save(domain, settings).block();
        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private String patchDomainSettings(Request request, Response response) {
        Domain domain = asDomain(request);
        if (request.body() == null || request.body().isEmpty()) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Request body is required")
                .haltError();
        }
        try {
            JsonNode node = OBJECT_MAPPER_DEFAULT.readTree(request.bodyAsBytes());
            DomainSettingsPatchRequest dto = OBJECT_MAPPER_DEFAULT.treeToValue(node, DomainSettingsPatchRequest.class);
            DomainSettingsPatch patch = new DomainSettingsPatch(
                parseUserSearchMode(node, dto),
                parseResourceSearchEnabled(node, dto),
                parseDefaultCalendarPublicVisibility(node, dto),
                parseCalendarPublicVisibilitySettingEnabled(node, dto));
            domainSettingsDAO.patch(domain, patch).block();
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message(e.getMessage())
                .haltError();
        } catch (Exception e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid request body")
                .haltError();
        }
        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
    }

    private ValuePatch<UserSearchMode> parseUserSearchMode(JsonNode node, DomainSettingsPatchRequest dto) {
        if (!node.has(FIELD_USER_SEARCH_MODE)) {
            return ValuePatch.keep();
        }
        return dto.userSearchMode().map(UserSearchMode::deserialize)
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<Boolean> parseResourceSearchEnabled(JsonNode node, DomainSettingsPatchRequest dto) {
        if (!node.has(FIELD_RESOURCE_SEARCH_ENABLED)) {
            return ValuePatch.keep();
        }
        return dto.resourceSearchEnabled().map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<DefaultCalendarPublicVisibility> parseDefaultCalendarPublicVisibility(JsonNode node, DomainSettingsPatchRequest dto) {
        if (!node.has(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY)) {
            return ValuePatch.keep();
        }
        return dto.defaultCalendarPublicVisibility().map(DefaultCalendarPublicVisibility::deserialize)
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private ValuePatch<Boolean> parseCalendarPublicVisibilitySettingEnabled(JsonNode node, DomainSettingsPatchRequest dto) {
        if (!node.has(FIELD_CALENDAR_PUBLIC_VISIBILITY_SETTING_ENABLED)) {
            return ValuePatch.keep();
        }
        return dto.calendarPublicVisibilitySettingEnabled().map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    private Domain asDomain(Request request) {
        String domainName = request.params(DOMAIN_PARAM);
        try {
            Domain domain = Domain.of(domainName);
            domainDAO.retrieve(domain)
                .blockOptional()
                .orElseThrow(() -> ErrorResponder.builder()
                    .statusCode(HttpStatus.NOT_FOUND_404)
                    .type(ErrorResponder.ErrorType.NOT_FOUND)
                    .message("Domain not found: %s".formatted(domainName))
                    .haltError());
            return domain;
        } catch (IllegalArgumentException e) {
            throw ErrorResponder.builder()
                .statusCode(HttpStatus.BAD_REQUEST_400)
                .type(ErrorResponder.ErrorType.INVALID_ARGUMENT)
                .message("Invalid domain: %s", domainName)
                .cause(e)
                .haltError();
        }
    }
}

