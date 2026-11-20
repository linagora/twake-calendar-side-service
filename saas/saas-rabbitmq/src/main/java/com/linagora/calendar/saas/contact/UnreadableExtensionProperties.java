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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Throwables;

import ezvcard.VCard;
import ezvcard.VCardDataType;
import ezvcard.VCardVersion;
import ezvcard.property.RawProperty;
import ezvcard.property.Uid;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;

/**
 * jscontact-tools 2.1.0 reads every parameter of an extension property as a number, and throws on any other value
 * (VCardUtils#getVCardPropParams): a card holding for instance {@code X-TEST;X-LABEL=Matrix:kept} cannot be converted.
 * Properties it reads otherwise, that it names in an internal list (SOCIALPROFILE, X-ABLABEL...), do have text
 * parameters: only the ones it cannot read are concerned, hence this is tried rather than described.
 */
final class UnreadableExtensionProperties {
    private static final VCard2JSContact VCARD_TO_JSCONTACT = VCard2JSContact.builder()
        .config(VCard2JSContactConfig.builder().build())
        .build();
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String V_CARD_PROPS = "vCardProps";
    private static final String GROUP = "group";
    private static final String VALUE = "VALUE";
    private static final String UNKNOWN_VALUE_TYPE = "unknown";

    static boolean isCauseOf(Exception e) {
        return Throwables.getRootCause(e) instanceof NumberFormatException;
    }

    static boolean hasUnreadableParameters(RawProperty property) {
        VCard probe = new VCard(VCardVersion.V4_0);
        probe.setUid(new Uid("probe"));
        probe.setFormattedName("probe");
        probe.addProperty(property);
        try {
            VCARD_TO_JSCONTACT.convert(probe);
            return false;
        } catch (CardException | RuntimeException e) {
            return isCauseOf(e);
        }
    }

    /**
     * Removes from the card the extension properties the converter cannot read, and returns them.
     */
    static List<RawProperty> detach(VCard vCard) {
        List<RawProperty> unreadableProperties = vCard.getExtendedProperties().stream()
            .filter(UnreadableExtensionProperties::hasUnreadableParameters)
            .toList();
        unreadableProperties.forEach(vCard::removeProperty);
        return unreadableProperties;
    }

    /**
     * Appends the given properties to the vCardProps of a JSContact card, as the converter would have, parameters included.
     */
    static void appendAsVCardProps(ObjectNode card, List<RawProperty> properties) {
        if (properties.isEmpty()) {
            return;
        }
        ArrayNode vCardProps = card.get(V_CARD_PROPS) instanceof ArrayNode existingProps ? existingProps : card.putArray(V_CARD_PROPS);
        properties.stream()
            .map(UnreadableExtensionProperties::toJCard)
            .forEach(vCardProps::add);
    }

    private static ArrayNode toJCard(RawProperty property) {
        return JSON.arrayNode()
            .add(lowerCase(property.getPropertyName()))
            .add(parameters(property))
            .add(valueType(property))
            .add(property.getValue());
    }

    private static ObjectNode parameters(RawProperty property) {
        ObjectNode parameters = JSON.objectNode();
        Optional.ofNullable(property.getGroup())
            .ifPresent(group -> parameters.put(GROUP, group));
        property.getParameters().getMap().entrySet().stream()
            .filter(parameter -> !Strings.CI.equals(parameter.getKey(), VALUE))
            .filter(parameter -> !parameter.getValue().isEmpty())
            .forEach(parameter -> parameters.set(lowerCase(parameter.getKey()), parameterValue(parameter)));
        return parameters;
    }

    private static JsonNode parameterValue(Map.Entry<String, List<String>> parameter) {
        List<String> values = parameter.getValue();
        if (values.size() == 1) {
            return JSON.textNode(values.getFirst());
        }
        ArrayNode multipleValues = JSON.arrayNode();
        values.forEach(multipleValues::add);
        return multipleValues;
    }

    private static String valueType(RawProperty property) {
        return Optional.ofNullable(property.getDataType())
            .map(VCardDataType::getName)
            .map(UnreadableExtensionProperties::lowerCase)
            .orElse(UNKNOWN_VALUE_TYPE);
    }

    private static String lowerCase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private UnreadableExtensionProperties() {
    }
}
