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

package com.linagora.calendar.restapi.routes;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;

import reactor.core.publisher.Mono;

/**
 * Parses a JSON request body, turning a missing, {@code null} or malformed body into an
 * {@link IllegalArgumentException} naming the problem, so that it is answered with a 400.
 */
public class JsonRequestBodyParser {
    public static final String INVALID_BODY_PREFIX = "Invalid request body: ";
    public static final String MISSING_BODY_MESSAGE = INVALID_BODY_PREFIX + "a JSON body is required";

    private final ObjectMapper objectMapper;

    public JsonRequestBodyParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> Mono<T> parse(String payload, Class<T> type) {
        return Mono.justOrEmpty(payload)
            .filter(StringUtils::isNotBlank)
            .flatMap(nonBlankPayload -> Mono.fromCallable(() -> objectMapper.readValue(nonBlankPayload, type)))
            .onErrorMap(JsonProcessingException.class, error -> new IllegalArgumentException(INVALID_BODY_PREFIX + describe(error), error))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(MISSING_BODY_MESSAGE)));
    }

    private String describe(JsonProcessingException error) {
        if (error instanceof PropertyBindingException propertyBindingError) {
            return "unknown field '" + propertyBindingError.getPropertyName() + "'";
        }
        if (error instanceof MismatchedInputException mismatchedInputError) {
            return describeMismatch(mismatchedInputError);
        }
        return error.getOriginalMessage();
    }

    private String describeMismatch(MismatchedInputException error) {
        String expectedType = describeExpectedType(error.getTargetType());
        if (error.getPath().isEmpty()) {
            return "the body must be " + expectedType;
        }
        return "'" + describePath(error.getPath()) + "' must be " + expectedType;
    }

    private String describePath(List<JsonMappingException.Reference> path) {
        return path.stream()
            .map(reference -> Optional.ofNullable(reference.getFieldName())
                .map(fieldName -> "." + fieldName)
                .orElseGet(() -> "[" + reference.getIndex() + "]"))
            .collect(Collectors.joining())
            .replaceFirst("^\\.", "");
    }

    private String describeExpectedType(Class<?> targetType) {
        return Optional.ofNullable(targetType)
            .map(this::describeKnownType)
            .orElse("a JSON object");
    }

    private String describeKnownType(Class<?> targetType) {
        if (CharSequence.class.isAssignableFrom(targetType)) {
            return "a string";
        }
        if (isArray(targetType)) {
            return "an array";
        }
        if (isNumber(targetType)) {
            return "a number";
        }
        return "a JSON object";
    }

    private boolean isArray(Class<?> targetType) {
        return Collection.class.isAssignableFrom(targetType) || targetType.isArray();
    }

    private boolean isNumber(Class<?> targetType) {
        return Number.class.isAssignableFrom(targetType)
            || (targetType.isPrimitive() && targetType != boolean.class && targetType != char.class);
    }
}
