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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import java.util.stream.IntStream;

import org.apache.james.util.ValuePatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.linagora.calendar.storage.OpenPaaSId;

class BookingLinkExtraAttendeeUtilTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final OpenPaaSId ATTENDEE_1 = new OpenPaaSId("659387b9d486dc0046aeffb1");
    private static final OpenPaaSId ATTENDEE_2 = new OpenPaaSId("659387b9d486dc0046aeffb2");

    private static JsonNode json(String value) {
        try {
            return OBJECT_MAPPER.readTree(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parseShouldReadParticipantsOfTheAndNode() {
        assertThat(BookingLinkExtraAttendeeUtil.parse(json("""
            {"and": [{"participant": "659387b9d486dc0046aeffb1"}, {"participant": "659387b9d486dc0046aeffb2"}]}""")))
            .isEqualTo(ExtraAttendees.of(ATTENDEE_1, ATTENDEE_2));
    }

    @Test
    void parseShouldAcceptEmptyAnd() {
        assertThat(BookingLinkExtraAttendeeUtil.parse(json("""
            {"and": []}""")))
            .isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    void parseShouldTrimParticipants() {
        assertThat(BookingLinkExtraAttendeeUtil.parse(json("""
            {"and": [{"participant": "  659387b9d486dc0046aeffb1  "}]}""")))
            .isEqualTo(ExtraAttendees.of(ATTENDEE_1));
    }

    @Test
    void parseShouldDeduplicateParticipants() {
        assertThat(BookingLinkExtraAttendeeUtil.parse(json("""
            {"and": [{"participant": "659387b9d486dc0046aeffb1"}, {"participant": "659387b9d486dc0046aeffb1"}]}""")))
            .isEqualTo(ExtraAttendees.of(ATTENDEE_1));
    }

    @Test
    void parseShouldDefaultToNoneWhenAbsent() {
        assertThat(BookingLinkExtraAttendeeUtil.parse(Optional.empty()))
            .isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    void parseShouldDefaultToNoneWhenNullNode() {
        // Jackson binds an absent 'extraAttendees' typed as Optional<JsonNode> to a NullNode, not to an empty
        // Optional: omitting the field must not be rejected.
        assertThat(BookingLinkExtraAttendeeUtil.parse(Optional.of(NullNode.instance)))
            .isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    void parsePatchShouldModifyToParticipants() {
        assertThat(BookingLinkExtraAttendeeUtil.parsePatch(Optional.of(json("""
            {"and": [{"participant": "659387b9d486dc0046aeffb1"}]}"""))))
            .isEqualTo(ValuePatch.modifyTo(ExtraAttendees.of(ATTENDEE_1)));
    }

    @Test
    void parsePatchShouldRemoveWhenNullNode() {
        assertThat(BookingLinkExtraAttendeeUtil.parsePatch(Optional.of(NullNode.instance)))
            .isEqualTo(ValuePatch.remove());
    }

    @Test
    void parsePatchShouldRemoveWhenEmptyAnd() {
        assertThat(BookingLinkExtraAttendeeUtil.parsePatch(Optional.of(json("""
            {"and": []}"""))))
            .isEqualTo(ValuePatch.remove());
    }

    @Test
    void parseShouldThrowWhenTooManyParticipants() {
        String participants = IntStream.rangeClosed(0, BookingLinkExtraAttendeeUtil.MAX_EXTRA_ATTENDEES)
            .mapToObj("{\"participant\": \"659387b9d486dc0046aef%03d\"}"::formatted)
            .reduce((left, right) -> left + ", " + right)
            .orElseThrow();

        assertThatThrownBy(() -> BookingLinkExtraAttendeeUtil.parse(json("{\"and\": [" + participants + "]}")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain more than 20 entries");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[\"659387b9d486dc0046aeffb1\"]",
        "\"659387b9d486dc0046aeffb1\"",
        "{}",
        "{\"and\": {}}",
        "{\"and\": [], \"or\": []}",
        "{\"or\": [{\"participant\": \"659387b9d486dc0046aeffb1\"}]}",
        "{\"and\": [{\"and\": [{\"participant\": \"659387b9d486dc0046aeffb1\"}]}]}",
        "{\"and\": [\"659387b9d486dc0046aeffb1\"]}",
        "{\"and\": [{\"participant\": 42}]}",
        "{\"and\": [{\"participant\": \"  \"}]}",
        "{\"and\": [{\"participant\": \"659387b9d486dc0046aeffb1\", \"role\": \"optional\"}]}"})
    void parseShouldRejectUnsupportedShapes(String payload) {
        assertThatThrownBy(() -> BookingLinkExtraAttendeeUtil.parse(json(payload)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void serializeShouldRoundTrip() {
        ExtraAttendees extraAttendees = ExtraAttendees.of(ATTENDEE_1, ATTENDEE_2);

        assertThat(BookingLinkExtraAttendeeUtil.parse(BookingLinkExtraAttendeeUtil.serialize(extraAttendees)))
            .isEqualTo(extraAttendees);
    }

    @Test
    void serializeShouldWriteAndOfParticipants() {
        assertThatJson(BookingLinkExtraAttendeeUtil.serialize(ExtraAttendees.of(ATTENDEE_1, ATTENDEE_2)).toString())
            .isEqualTo("""
                {"and": [{"participant": "659387b9d486dc0046aeffb1"}, {"participant": "659387b9d486dc0046aeffb2"}]}""");
    }

    @Test
    void serializeShouldWriteEmptyAndWhenNone() {
        assertThatJson(BookingLinkExtraAttendeeUtil.serialize(ExtraAttendees.NONE).toString())
            .isEqualTo("""
                {"and": []}""");
    }
}
