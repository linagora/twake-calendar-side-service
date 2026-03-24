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

import static com.linagora.calendar.storage.booking.BookingLinkInsertRequest.ACTIVE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.Username;
import org.apache.james.utils.UpdatableTickingClock;
import org.apache.james.util.ValuePatch;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.booking.AvailabilityRule.WeeklyAvailabilityRule;
import com.linagora.calendar.api.booking.AvailabilityRules;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;

public interface BookingLinkDAOContract {
    Username USER_1 = Username.of("user1@linagora.com");
    Username USER_2 = Username.of("user2@linagora.com");
    CalendarURL CALENDAR_URL = new CalendarURL(new OpenPaaSId("659387b9d486dc0046aeff91"), new OpenPaaSId("659387b9d486dc0046aeff92"));
    Duration EVENT_DURATION = Duration.ofMinutes(30);
    AvailabilityRules AVAILABILITY_RULES = AvailabilityRules.of(new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("17:00")));
    BookingLinkInsertRequest INSERT_REQUEST = new BookingLinkInsertRequest(CALENDAR_URL, EVENT_DURATION, ACTIVE, Optional.of(AVAILABILITY_RULES));
    CalendarURL UPDATED_CALENDAR_URL = new CalendarURL(new OpenPaaSId("659387b9d486dc0046aeffaa"), new OpenPaaSId("659387b9d486dc0046aeffab"));
    Duration UPDATED_DURATION = Duration.ofMinutes(45);
    AvailabilityRules UPDATED_AVAILABILITY_RULES = AvailabilityRules.of(new WeeklyAvailabilityRule(DayOfWeek.FRIDAY, LocalTime.parse("08:00"), LocalTime.parse("12:00")));

    BookingLinkDAO testee();

    UpdatableTickingClock clock();

    @Test
    default void insertShouldReturnCreatedBookingLink() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        BookingLink expected = BookingLink.builder()
            .username(USER_1)
            .publicId(new BookingLinkPublicId(UUID.randomUUID()))
            .calendarUrl(CALENDAR_URL)
            .duration(EVENT_DURATION)
            .active(ACTIVE)
            .availabilityRules(Optional.of(AVAILABILITY_RULES))
            .createdAt(clock().instant())
            .updatedAt(clock().instant())
            .build();

        assertThat(created)
            .isNotNull()
            .usingRecursiveComparison()
            .ignoringFields("publicId")
            .isEqualTo(expected);

        assertThat(created.publicId())
            .isNotNull();
    }

    @Test
    default void insertTwiceShouldReturnDifferentPublicIds() {
        BookingLink first = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLink second = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();

        assertThat(first.publicId())
            .isNotEqualTo(second.publicId());
    }

    @Test
    default void insertThenFindByPublicIdShouldReturnInsertedBookingLink() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        BookingLink found = testee().findByPublicId(USER_1, inserted.publicId()).block();

        assertThat(found)
            .isEqualTo(inserted);
    }

    @Test
    default void findByPublicIdShouldReturnEmptyWhenPublicIdDoesNotExist() {
        BookingLinkPublicId missingPublicId = new BookingLinkPublicId(UUID.randomUUID());

        assertThat(testee().findByPublicId(USER_1, missingPublicId).blockOptional())
            .isEmpty();
    }

    @Test
    default void findByPublicIdShouldReturnEmptyWhenPublicIdBelongsToAnotherUsername() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(testee().findByPublicId(USER_2, inserted.publicId()).blockOptional())
            .isEmpty();
    }

    @Test
    default void findByPublicIdShouldReturnInsertedBookingLink() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        BookingLink found = testee().findByPublicId(inserted.publicId()).block();

        assertThat(found)
            .isEqualTo(inserted);
    }

    @Test
    default void findByPublicIdWithoutUsernameShouldReturnEmptyWhenPublicIdDoesNotExist() {
        BookingLinkPublicId missingPublicId = new BookingLinkPublicId(UUID.randomUUID());

        assertThat(testee().findByPublicId(missingPublicId).blockOptional())
            .isEmpty();
    }

    @Test
    default void findByUsernameShouldReturnEmptyByDefault() {
        assertThat(testee().findByUsername(USER_1).collectList().block())
            .isEmpty();
    }

    @Test
    default void findByUsernameShouldReturnListWhenInsertMultipleTimes() {
        BookingLink first = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLink second = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(testee().findByUsername(USER_1).collectList().block())
            .containsExactlyInAnyOrder(first, second);
    }

    @Test
    default void findByUsernameShouldNotContainElementsOfAnotherUsername() {
        BookingLink user1Link = testee().insert(USER_1, INSERT_REQUEST).block();
        testee().insert(USER_2, INSERT_REQUEST).block();

        assertThat(testee().findByUsername(USER_1).collectList().block())
            .extracting(BookingLink::publicId)
            .containsExactly(user1Link.publicId());
    }

    @Test
    default void findByUsernameShouldReturnSortedByUpdatedAtDescending() {
        BookingLink first = testee().insert(USER_1, INSERT_REQUEST).block();

        clock().setInstant(clock().instant().plusSeconds(1));
        BookingLink second = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(testee().findByUsername(USER_1).collectList().block())
            .containsExactly(second, first);
    }

    @Test
    default void updateShouldFailWhenPublicIdDoesNotExist() {
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.modifyTo(UPDATED_CALENDAR_URL),
            ValuePatch.keep(),
            ValuePatch.keep(),
            ValuePatch.keep());

        assertThatThrownBy(() -> testee().update(USER_1, new BookingLinkPublicId(UUID.randomUUID()), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void updateShouldFailWhenPublicIdBelongsToAnotherUsername() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.modifyTo(UPDATED_CALENDAR_URL),
            ValuePatch.keep(),
            ValuePatch.keep(),
            ValuePatch.keep());

        assertThatThrownBy(() -> testee().update(USER_2, inserted.publicId(), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void updateFailureShouldNotChangeExistingData() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.modifyTo(UPDATED_CALENDAR_URL),
            ValuePatch.keep(),
            ValuePatch.keep(),
            ValuePatch.keep());

        assertThatThrownBy(() -> testee().update(USER_2, inserted.publicId(), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);

        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block())
            .isEqualTo(inserted);
    }

    @Test
    default void updateShouldApplyNewValues() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.modifyTo(UPDATED_CALENDAR_URL),
            ValuePatch.modifyTo(UPDATED_DURATION),
            ValuePatch.modifyTo(!ACTIVE),
            ValuePatch.modifyTo(UPDATED_AVAILABILITY_RULES));
        clock().setInstant(inserted.updatedAt().plusSeconds(1));

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated)
            .usingRecursiveComparison()
            .isEqualTo(BookingLink.builder()
                .username(inserted.username())
                .publicId(inserted.publicId())
                .calendarUrl(UPDATED_CALENDAR_URL)
                .duration(UPDATED_DURATION)
                .active(!ACTIVE)
                .availabilityRules(Optional.of(UPDATED_AVAILABILITY_RULES))
                .createdAt(inserted.createdAt())
                .updatedAt(clock().instant())
                .build());
    }

    @Test
    default void updateShouldUpdateExistingRecordWithoutCreatingNewOne() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.keep(),
            ValuePatch.modifyTo(UPDATED_DURATION),
            ValuePatch.keep(),
            ValuePatch.keep());

        assertThat(testee().findByUsername(USER_1).collectList().block())
            .hasSize(1);

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(testee().findByUsername(USER_1).collectList().block())
            .hasSize(1)
            .containsExactly(updated);

        assertThat(updated)
            .usingRecursiveComparison()
            .ignoringFields("duration", "updatedAt")
            .isEqualTo(inserted);
        assertThat(updated.duration())
            .isEqualTo(UPDATED_DURATION);
    }

    @Test
    default void updateShouldAllowResetAvailabilityRules() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = new BookingLinkPatchRequest(
            ValuePatch.keep(),
            ValuePatch.keep(),
            ValuePatch.keep(),
            ValuePatch.remove());

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.availabilityRules()).isEmpty();
    }

    @Test
    default void resetPublicIdShouldFailWhenPublicIdDoesNotExist() {
        assertThatThrownBy(() -> testee().resetPublicId(USER_1, new BookingLinkPublicId(UUID.randomUUID())).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void resetPublicIdShouldFailWhenPublicIdBelongsToAnotherUsername() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThatThrownBy(() -> testee().resetPublicId(USER_2, inserted.publicId()).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void resetPublicIdFailureShouldNotChangeExistingData() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThatThrownBy(() -> testee().resetPublicId(USER_2, inserted.publicId()).block())
            .isInstanceOf(BookingLinkNotFoundException.class);

        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block())
            .isEqualTo(inserted);
    }

    @Test
    default void resetPublicIdShouldReturnNewPublicIdDifferentFromOldOne() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        BookingLinkPublicId newPublicId = testee().resetPublicId(USER_1, inserted.publicId()).block();

        assertThat(newPublicId)
            .isNotNull()
            .isNotEqualTo(inserted.publicId());
    }

    @Test
    default void resetPublicIdShouldKeepOnlyNewPublicId() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        BookingLinkPublicId newPublicId = testee().resetPublicId(USER_1, inserted.publicId()).block();

        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).blockOptional())
            .isEmpty();
        assertThat(testee().findByPublicId(USER_1, newPublicId).blockOptional())
            .isPresent();
        assertThat(testee().findByUsername(USER_1).collectList().block())
            .extracting(BookingLink::publicId)
            .containsExactly(newPublicId);
    }

    @Test
    default void resetPublicIdShouldOnlyChangePublicIdAndUpdatedAt() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        clock().setInstant(clock().instant().plusSeconds(1));

        BookingLinkPublicId newPublicId = testee().resetPublicId(USER_1, inserted.publicId()).block();
        BookingLink resetBookingLink = testee().findByPublicId(USER_1, newPublicId).block();

        assertThat(resetBookingLink)
            .usingRecursiveComparison()
            .ignoringFields("publicId", "updatedAt")
            .isEqualTo(inserted);

        assertThat(resetBookingLink.publicId()).isNotEqualTo(inserted.publicId());
        assertThat(resetBookingLink.updatedAt()).isEqualTo(clock().instant());
    }

    @Test
    default void deleteShouldRemoveBookingLinkFromFindByPublicId() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        testee().delete(USER_1, inserted.publicId()).block();

        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).blockOptional())
            .isEmpty();
    }

    @Test
    default void deleteShouldBeIdempotent() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        testee().delete(USER_1, inserted.publicId()).block();
        assertThatCode(() -> testee().delete(USER_1, inserted.publicId()).block())
            .doesNotThrowAnyException();
    }

    @Test
    default void deleteWithAnotherUsernameShouldNotDeleteExistingBookingLink() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();

        testee().delete(USER_2, inserted.publicId()).block();
        assertThat(testee().findByUsername(USER_1).collectList().block())
            .containsExactly(inserted);
    }

}
