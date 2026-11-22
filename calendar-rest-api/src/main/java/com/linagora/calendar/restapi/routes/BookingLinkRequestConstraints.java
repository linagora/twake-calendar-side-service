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

import java.time.Duration;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.exc.PropertyBindingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.common.base.Preconditions;

/**
 * Bounds and strict JSON typing shared by every booking link write API (REST create / patch, WebAdmin).
 */
public final class BookingLinkRequestConstraints {

    public enum TextField {
        NAME("name", 255),
        DESCRIPTION("description", 4096),
        LOCATION("location", 1024);

        private final String fieldName;
        private final int maxLength;

        TextField(String fieldName, int maxLength) {
            this.fieldName = fieldName;
            this.maxLength = maxLength;
        }

        public int maxLength() {
            return maxLength;
        }

        public Optional<String> normalize(Optional<String> value) {
            return value.map(String::trim)
                .filter(text -> !text.isEmpty())
                .map(this::checkLength);
        }

        private String checkLength(String text) {
            Preconditions.checkArgument(text.length() <= maxLength,
                "'%s' must not exceed %s characters", fieldName, maxLength);
            return text;
        }
    }

    /**
     * A slot can not be longer than the widest range the slots route accepts.
     */
    public static final Duration MAX_DURATION = BookingLinkSlotsRoute.MAX_QUERY_RANGE;

    /**
     * Refuses the default Jackson coercions: numbers or strings as booleans, numbers or booleans as strings,
     * strings or floats as integers.
     */
    public static final ObjectMapper STRICT_OBJECT_MAPPER = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .addModule(new Jdk8Module())
        .withCoercionConfig(LogicalType.Boolean, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Textual, config -> config
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .withCoercionConfig(LogicalType.Integer, config -> config
            .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
        .build();

    public static Duration toDuration(int durationMinutes) {
        Preconditions.checkArgument(durationMinutes > 0, "'durationMinutes' must be positive");
        Preconditions.checkArgument(durationMinutes <= MAX_DURATION.toMinutes(),
            "'durationMinutes' must not exceed %s (%s days)", MAX_DURATION.toMinutes(), MAX_DURATION.toDays());
        return Duration.ofMinutes(durationMinutes);
    }

    public static IllegalArgumentException asInvalidBody(Exception e) {
        if (e.getCause() instanceof IllegalArgumentException illegalArgumentException) {
            return illegalArgumentException;
        }
        if (isWronglyTypedField(e)) {
            return new IllegalArgumentException("Invalid request body: '" + fieldPath((MismatchedInputException) e)
                + "' has an invalid value or JSON type", e);
        }
        return new IllegalArgumentException("Invalid request body", e);
    }

    private static boolean isWronglyTypedField(Exception e) {
        return e instanceof MismatchedInputException mismatchedInputException
            && !(e instanceof PropertyBindingException)
            && !mismatchedInputException.getPath().isEmpty();
    }

    private static String fieldPath(MismatchedInputException e) {
        return e.getPath().stream()
            .map(reference -> Optional.ofNullable(reference.getFieldName())
                .orElseGet(() -> String.valueOf(reference.getIndex())))
            .collect(Collectors.joining("."));
    }

    private BookingLinkRequestConstraints() {
    }
}
