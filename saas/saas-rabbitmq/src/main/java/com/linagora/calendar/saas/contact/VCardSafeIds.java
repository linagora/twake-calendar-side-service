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
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;

/**
 * Labelled entries are written as vCard group "G-&lt;id&gt;", and group names only allow [A-Za-z0-9-] (RFC 6350).
 * Ids are local to the card, so unsafe ones are replaced by their SHA-1 to keep stored cards readable back, along with
 * what refers to them: the organizationId of titles and the JSON pointers of localizations.
 */
final class VCardSafeIds {
    private static final Set<String> ID_KEYED_PROPERTIES = Set.of("addresses", "anniversaries", "calendars", "cryptoKeys",
        "directories", "emails", "links", "media", "nicknames", "notes", "onlineServices", "organizations", "personalInfo",
        "phones", "preferredLanguages", "schedulingAddresses", "titles");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final String ORGANIZATIONS = "organizations";
    private static final String TITLES = "titles";
    private static final String ORGANIZATION_ID = "organizationId";
    private static final String LOCALIZATIONS = "localizations";
    private static final char POINTER_SEPARATOR = '/';
    private static final int POINTER_PARTS = 3;

    static void renameUnsafeIds(ObjectNode card) {
        VCardSafeIds safeIds = new VCardSafeIds(card);
        ID_KEYED_PROPERTIES.forEach(property -> safeIds.renameIdMap(property, card.get(property)));
        StreamSupport.stream(card.path(LOCALIZATIONS).spliterator(), false)
            .forEach(safeIds::renamePatches);
    }

    private final Map<String, Set<String>> idsToKeepPerProperty;

    private VCardSafeIds(ObjectNode card) {
        this.idsToKeepPerProperty = ID_KEYED_PROPERTIES.stream()
            .collect(Collectors.toUnmodifiableMap(property -> property, property -> safeIds(card.path(property))));
    }

    private static Set<String> safeIds(JsonNode idMap) {
        return idMap.properties().stream()
            .map(Map.Entry::getKey)
            .filter(VCardSafeIds::isSafe)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static boolean isSafe(String id) {
        return SAFE_ID.matcher(id).matches();
    }

    // Hashes again when the hash is the id of another entry: both entries have to survive the renaming.
    private String safeId(String property, String id) {
        if (isSafe(id)) {
            return id;
        }
        Set<String> idsToKeep = idsToKeepPerProperty.get(property);
        return Stream.iterate(CollectedContact.sha1(id), CollectedContact::sha1)
            .filter(candidate -> !idsToKeep.contains(candidate))
            .findFirst()
            .orElseThrow();
    }

    private void renameIdMap(String property, JsonNode idMap) {
        if (idMap instanceof ObjectNode entries) {
            rewriteEntries(entries, id -> safeId(property, id), (id, entry) -> entry);
            if (TITLES.equals(property)) {
                entries.forEach(this::renameOrganizationReference);
            }
        }
    }

    private void renameOrganizationReference(JsonNode title) {
        if (title instanceof ObjectNode titleNode && titleNode.path(ORGANIZATION_ID).isTextual()) {
            titleNode.put(ORGANIZATION_ID, safeId(ORGANIZATIONS, titleNode.get(ORGANIZATION_ID).asText()));
        }
    }

    private void renamePatches(JsonNode patches) {
        if (patches instanceof ObjectNode patchNodes) {
            rewriteEntries(patchNodes, this::renamePointer, this::renamePatchValue);
        }
    }

    private String renamePointer(String pointer) {
        List<String> parts = pointerParts(pointer);
        if (parts.size() == 1 || !ID_KEYED_PROPERTIES.contains(parts.getFirst())) {
            return pointer;
        }
        String safeId = safeId(parts.getFirst(), unescapePointerToken(parts.get(1)));
        return Joiner.on(POINTER_SEPARATOR).join(Stream.concat(
            Stream.of(parts.getFirst(), safeId),
            parts.stream().skip(2)).toList());
    }

    private JsonNode renamePatchValue(String pointer, JsonNode value) {
        List<String> parts = pointerParts(pointer);
        String property = parts.getFirst();
        if (!ID_KEYED_PROPERTIES.contains(property)) {
            return value;
        }
        if (parts.size() == 1) {
            renameIdMap(property, value);
            return value;
        }
        if (!TITLES.equals(property)) {
            return value;
        }
        if (parts.size() == 2) {
            renameOrganizationReference(value);
            return value;
        }
        return renameOrganizationIdPatch(parts.get(2), value);
    }

    private JsonNode renameOrganizationIdPatch(String titleProperty, JsonNode value) {
        if (ORGANIZATION_ID.equals(titleProperty) && value.isTextual()) {
            return TextNode.valueOf(safeId(ORGANIZATIONS, value.asText()));
        }
        return value;
    }

    private static List<String> pointerParts(String pointer) {
        return Splitter.on(POINTER_SEPARATOR).limit(POINTER_PARTS).splitToList(pointer);
    }

    // RFC 6901: "/" and "~" are escaped within a JSON pointer token.
    private static String unescapePointerToken(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    private static void rewriteEntries(ObjectNode node,
                                       UnaryOperator<String> keyRewriter,
                                       BiFunction<String, JsonNode, JsonNode> valueRewriter) {
        List<Map.Entry<String, JsonNode>> entries = List.copyOf(node.properties());
        node.removeAll();
        entries.forEach(entry -> node.set(keyRewriter.apply(entry.getKey()), valueRewriter.apply(entry.getKey(), entry.getValue())));
    }
}
