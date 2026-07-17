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

package com.linagora.calendar.storage.booking;

import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.util.ValuePatch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

/**
 * JSON representation of the {@link ExtraAttendees} tree:
 *
 * <pre>{@code {"and": [{"participant": "id1"}, {"participant": "id2"}]}}</pre>
 *
 * Only that shape - a single 'and' of 'participant' leaves - is accepted for now, richer trees being left for
 * later.
 */
public class BookingLinkExtraAttendeeUtil {

    public static final int MAX_EXTRA_ATTENDEES = 20;

    private static final String FIELD_AND = "and";
    private static final String FIELD_PARTICIPANT = "participant";

    public static ExtraAttendees parse(Optional<JsonNode> raw) {
        return value(raw)
            .map(BookingLinkExtraAttendeeUtil::parse)
            .orElse(BookingLinkInsertRequest.NO_EXTRA_ATTENDEE);
    }

    /**
     * Parses the 'extraAttendees' of a patch request, the caller being responsible for telling an absent field
     * (keep) from a present one.
     */
    public static ValuePatch<ExtraAttendees> parsePatch(Optional<JsonNode> raw) {
        return value(raw)
            .map(BookingLinkExtraAttendeeUtil::parse)
            .filter(extraAttendees -> !extraAttendees.isEmpty())
            .map(ValuePatch::modifyTo)
            .orElseGet(ValuePatch::remove);
    }

    public static ExtraAttendees parse(JsonNode raw) {
        Preconditions.checkArgument(raw.isObject() && raw.size() == 1 && raw.has(FIELD_AND),
            "'extraAttendees' must be an object holding a single 'and' field");
        JsonNode and = raw.get(FIELD_AND);
        Preconditions.checkArgument(and.isArray(), "'extraAttendees.and' must be an array");

        List<OpenPaaSId> participants = StreamSupport.stream(and.spliterator(), false)
            .map(BookingLinkExtraAttendeeUtil::parseParticipant)
            .distinct()
            .toList();
        Preconditions.checkArgument(participants.size() <= MAX_EXTRA_ATTENDEES,
            "'extraAttendees' must not contain more than %s entries", MAX_EXTRA_ATTENDEES);
        return ExtraAttendees.of(participants);
    }

    public static JsonNode serialize(ExtraAttendees extraAttendees) {
        return serialize(extraAttendees.root());
    }

    /**
     * Jackson binds an absent as well as an explicitly null field typed as {@code Optional<JsonNode>} to a
     * {@link com.fasterxml.jackson.databind.node.NullNode}, rather than to an empty Optional: hold both as no
     * value at all.
     */
    private static Optional<JsonNode> value(Optional<JsonNode> raw) {
        return raw.filter(node -> !node.isNull());
    }

    private static OpenPaaSId parseParticipant(JsonNode node) {
        Preconditions.checkArgument(node.isObject() && node.size() == 1 && node.has(FIELD_PARTICIPANT),
            "'extraAttendees.and' entries must be objects holding a single 'participant' field");
        JsonNode participant = node.get(FIELD_PARTICIPANT);
        Preconditions.checkArgument(participant.isTextual(), "'participant' must be a string");
        String value = StringUtils.trimToEmpty(participant.asText());
        Preconditions.checkArgument(!value.isEmpty(), "'participant' must not be blank");
        return new OpenPaaSId(value);
    }

    private static JsonNode serialize(ExtraAttendeeNode node) {
        return switch (node) {
            case ExtraAttendeeNode.Participant participant -> JsonNodeFactory.instance.objectNode()
                .put(FIELD_PARTICIPANT, participant.id().value());
            case ExtraAttendeeNode.And and -> {
                ArrayNode children = JsonNodeFactory.instance.arrayNode();
                and.children().stream()
                    .map(BookingLinkExtraAttendeeUtil::serialize)
                    .forEach(children::add);
                ObjectNode result = JsonNodeFactory.instance.objectNode();
                result.set(FIELD_AND, children);
                yield result;
            }
        };
    }

    private BookingLinkExtraAttendeeUtil() {
    }
}
