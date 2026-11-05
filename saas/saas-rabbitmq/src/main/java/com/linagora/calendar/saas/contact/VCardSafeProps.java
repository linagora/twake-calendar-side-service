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

package com.linagora.calendar.saas.contact;

import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * vCardProps are written as is as vCard properties (validation being off), and one that a vCard cannot carry would cost
 * the whole contact: the conversion fails, or the written card cannot be read back. Such properties are dropped, the
 * contact being worth more than an extension property:
 * <ul>
 *     <li>what is not a [name, parameters, value type, value] jCard property with a scalar value,</li>
 *     <li>names and value types that RFC 6350 does not allow,</li>
 *     <li>properties that would change the structure of the card: BEGIN, END, UID and any VERSION but the written one,</li>
 *     <li>any other that does not go through a vCard. What the reader refuses depends on the library: it refuses for
 *     instance any parameter that is not an extension and not a number, hence this is tried rather than described.</li>
 * </ul>
 */
final class VCardSafeProps {
    private static final Logger LOGGER = LoggerFactory.getLogger(VCardSafeProps.class);
    private static final String V_CARD_PROPS = "vCardProps";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9-]+");
    private static final Set<String> STRUCTURAL_NAMES = Set.of("begin", "end", "uid");
    private static final String VERSION = "version";
    private static final String WRITTEN_VERSION = "4.0";
    private static final int NAME_INDEX = 0;
    private static final int PARAMETERS_INDEX = 1;
    private static final int VALUE_TYPE_INDEX = 2;
    private static final int VALUE_INDEX = 3;

    static void dropUnsafeProps(ObjectNode card, Predicate<ObjectNode> goesThroughAVCard) {
        if (card.get(V_CARD_PROPS) instanceof ArrayNode properties) {
            ArrayNode safeProperties = properties.arrayNode();
            StreamSupport.stream(properties.spliterator(), false)
                .filter(property -> isSafe(property, goesThroughAVCard))
                .forEach(safeProperties::add);
            if (safeProperties.size() != properties.size()) {
                LOGGER.info("Dropping {} vCardProps of a collected contact that a vCard cannot carry", properties.size() - safeProperties.size());
            }
            if (safeProperties.isEmpty()) {
                // jscontact-tools serializes an empty list as malformed JSON
                card.remove(V_CARD_PROPS);
            } else {
                card.set(V_CARD_PROPS, safeProperties);
            }
        }
    }

    private static boolean isSafe(JsonNode property, Predicate<ObjectNode> goesThroughAVCard) {
        return hasValidShape(property)
            && !changesStructure(property)
            && goesThroughAVCard.test(cardHolding(property));
    }

    private static boolean hasValidShape(JsonNode property) {
        return property.isArray()
            && property.size() > VALUE_INDEX
            && isValidName(property.get(NAME_INDEX))
            && property.get(PARAMETERS_INDEX).isObject()
            && isValidName(property.get(VALUE_TYPE_INDEX))
            && property.get(VALUE_INDEX).isValueNode()
            && !property.get(VALUE_INDEX).isNull();
    }

    private static boolean isValidName(JsonNode name) {
        return name.isTextual() && NAME.matcher(name.asText()).matches();
    }

    private static boolean changesStructure(JsonNode property) {
        String name = property.get(NAME_INDEX).asText().toLowerCase(Locale.ROOT);
        return STRUCTURAL_NAMES.contains(name)
            || (VERSION.equals(name) && !WRITTEN_VERSION.equals(property.get(VALUE_INDEX).asText()));
    }

    private static ObjectNode cardHolding(JsonNode property) {
        ObjectNode card = JsonNodeFactory.instance.objectNode()
            .put("@type", "Card")
            .put("version", "2.0")
            .put("uid", "probe");
        card.putArray(V_CARD_PROPS).add(property);
        return card;
    }

    private VCardSafeProps() {
    }
}
