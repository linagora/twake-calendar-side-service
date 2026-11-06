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
import java.util.Set;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;

/**
 * A field holding only what JSContact does not define, such as a name with neither full nor components, is written to the
 * vCard in a form that cannot be read back, and would cost the whole contact. What makes it fail depends on the library,
 * hence this is tried rather than described: when a card does not go through a vCard, the fields, or the entries of a map,
 * that fail on a card of their own are dropped.
 */
final class VCardSafeFields {
    private static final Logger LOGGER = LoggerFactory.getLogger(VCardSafeFields.class);
    private static final Set<String> IDENTIFYING_FIELDS = Set.of("@type", "version", "uid", "vCardProps");

    static void dropUnsafeFields(ObjectNode card, Predicate<ObjectNode> goesThroughAVCard) {
        if (goesThroughAVCard.test(card)) {
            return;
        }
        names(card).stream()
            .filter(name -> !IDENTIFYING_FIELDS.contains(name))
            .forEach(name -> dropIfUnsafe(card, name, goesThroughAVCard));
    }

    private static void dropIfUnsafe(ObjectNode card, String name, Predicate<ObjectNode> goesThroughAVCard) {
        JsonNode field = card.get(name);
        if (isMap(field)) {
            dropUnsafeEntries(card, name, (ObjectNode) field, goesThroughAVCard);
        } else if (!goesThroughAVCard.test(cardHolding(name, field))) {
            LOGGER.info("Dropping {} of a collected contact, that a vCard cannot carry", name);
            card.remove(name);
        }
    }

    private static void dropUnsafeEntries(ObjectNode card, String name, ObjectNode entries, Predicate<ObjectNode> goesThroughAVCard) {
        names(entries).stream()
            .filter(id -> !goesThroughAVCard.test(cardHolding(name, JsonNodeFactory.instance.objectNode().set(id, entries.get(id)))))
            .forEach(id -> {
                LOGGER.info("Dropping an entry of {} of a collected contact, that a vCard cannot carry", name);
                entries.remove(id);
            });
        if (entries.isEmpty()) {
            card.remove(name);
        }
    }

    private static boolean isMap(JsonNode field) {
        return field.isObject() && !field.isEmpty() && Streams.stream(field.elements()).allMatch(JsonNode::isObject);
    }

    private static List<String> names(ObjectNode node) {
        return ImmutableList.copyOf(node.fieldNames());
    }

    private static ObjectNode cardHolding(String name, JsonNode field) {
        ObjectNode card = JsonNodeFactory.instance.objectNode()
            .put("@type", "Card")
            .put("version", "2.0")
            .put("uid", "probe");
        card.set(name, field);
        return card;
    }

    private VCardSafeFields() {
    }
}
