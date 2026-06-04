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

import jakarta.annotation.Nullable;
import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.webadmin.Constants;
import org.apache.james.webadmin.Routes;
import org.apache.james.webadmin.utils.ErrorResponder;
import org.apache.james.webadmin.utils.JsonExtractException;
import org.apache.james.webadmin.utils.JsonExtractor;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.eclipse.jetty.http.HttpStatus;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.linagora.calendar.storage.DefaultCalendarPublicVisibility;
import com.linagora.calendar.storage.DomainSettings;
import com.linagora.calendar.storage.DomainSettingsDAO;
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

    public record DomainSettingsDTO(@JsonProperty(value = "userSearchMode", required = true) @Nullable String userSearchMode,
                                    @JsonProperty(value = "resourceSearchEnabled", required = true) @Nullable Boolean resourceSearchEnabled,
                                    @JsonProperty(value = "defaultCalendarPublicVisibility", required = true) @Nullable String defaultCalendarPublicVisibility) {

        static DomainSettingsDTO from(DomainSettings settings) {
            return new DomainSettingsDTO(
                settings.userSearchMode().map(UserSearchMode::serialize).orElse(null),
                settings.resourceSearchEnabled().orElse(null),
                settings.defaultCalendarPublicVisibility().map(DefaultCalendarPublicVisibility::serialize).orElse(null));
        }

        DomainSettings toDomainSettings() {
            DomainSettings.Builder builder = DomainSettings.builder();
            Optional.ofNullable(userSearchMode).map(UserSearchMode::deserialize).ifPresent(builder::userSearchMode);
            Optional.ofNullable(resourceSearchEnabled).ifPresent(builder::resourceSearchEnabled);
            Optional.ofNullable(defaultCalendarPublicVisibility)
                .map(DefaultCalendarPublicVisibility::deserialize)
                .ifPresent(builder::defaultCalendarPublicVisibility);
            return builder.build();
        }
    }

    private final DomainSettingsDAO domainSettingsDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final JsonTransformer jsonTransformer;
    private final JsonExtractor<DomainSettingsDTO> jsonExtractor;

    @Inject
    public DomainSettingsRoutes(DomainSettingsDAO domainSettingsDAO,
                                OpenPaaSDomainDAO domainDAO,
                                JsonTransformer jsonTransformer) {
        this.domainSettingsDAO = domainSettingsDAO;
        this.domainDAO = domainDAO;
        this.jsonTransformer = jsonTransformer;
        this.jsonExtractor = new JsonExtractor<>(DomainSettingsDTO.class);
    }

    @Override
    public String getBasePath() {
        return BASE_PATH;
    }

    @Override
    public void define(Service service) {
        service.get(DOMAIN_SETTINGS_PATH, this::getDomainSettings, jsonTransformer);
        service.put(DOMAIN_SETTINGS_PATH, this::putDomainSettings);
    }

    private DomainSettingsDTO getDomainSettings(Request request, Response response) {
        Domain domain = asDomain(request);
        return domainSettingsDAO.retrieve(domain)
            .defaultIfEmpty(DomainSettings.DEFAULT_DOMAIN_SETTINGS)
            .map(DomainSettingsDTO::from)
            .block();
    }

    private String putDomainSettings(Request request, Response response) throws JsonExtractException {
        Domain domain = asDomain(request);
        DomainSettingsDTO requestDTO = jsonExtractor.parse(request.body());
        DomainSettings settings = requestDTO.toDomainSettings();
        domainSettingsDAO.save(domain, settings).block();
        response.status(HttpStatus.NO_CONTENT_204);
        return Constants.EMPTY_BODY;
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

