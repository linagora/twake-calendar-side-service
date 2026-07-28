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
import java.util.List;
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
import com.linagora.calendar.storage.model.ResourceId;

public interface BookingLinkDAOContract {
    Username USER_1 = Username.of("user1@linagora.com");
    Username USER_2 = Username.of("user2@linagora.com");
    CalendarURL CALENDAR_URL = new CalendarURL(new OpenPaaSId("659387b9d486dc0046aeff91"), new OpenPaaSId("659387b9d486dc0046aeff92"));
    Duration EVENT_DURATION = Duration.ofMinutes(30);
    AvailabilityRules AVAILABILITY_RULES = AvailabilityRules.of(new WeeklyAvailabilityRule(DayOfWeek.MONDAY, LocalTime.parse("09:00"), LocalTime.parse("17:00")));
    BookingLinkInsertRequest INSERT_REQUEST = BookingLinkInsertRequest.builder().calendarUrl(CALENDAR_URL).eventDuration(EVENT_DURATION).availabilityRules(AVAILABILITY_RULES).build();
    CalendarURL UPDATED_CALENDAR_URL = new CalendarURL(new OpenPaaSId("659387b9d486dc0046aeffaa"), new OpenPaaSId("659387b9d486dc0046aeffab"));
    Duration UPDATED_DURATION = Duration.ofMinutes(45);
    AvailabilityRules UPDATED_AVAILABILITY_RULES = AvailabilityRules.of(new WeeklyAvailabilityRule(DayOfWeek.FRIDAY, LocalTime.parse("08:00"), LocalTime.parse("12:00")));
    OpenPaaSId EXTRA_ATTENDEE_1 = new OpenPaaSId("659387b9d486dc0046aeffb1");
    OpenPaaSId EXTRA_ATTENDEE_2 = new OpenPaaSId("659387b9d486dc0046aeffb2");
    ResourceId RESOURCE_1 = new ResourceId("659387b9d486dc0046aeffc1");
    ResourceId RESOURCE_2 = new ResourceId("659387b9d486dc0046aeffc2");
    BookingLinkAlarm ALARM = new BookingLinkAlarm("-PT10M");

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
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .calendarUrl(ValuePatch.modifyTo(UPDATED_CALENDAR_URL))
            .build();

        assertThatThrownBy(() -> testee().update(USER_1, new BookingLinkPublicId(UUID.randomUUID()), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void updateShouldFailWhenPublicIdBelongsToAnotherUsername() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .calendarUrl(ValuePatch.modifyTo(UPDATED_CALENDAR_URL))
            .build();

        assertThatThrownBy(() -> testee().update(USER_2, inserted.publicId(), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);
    }

    @Test
    default void updateFailureShouldNotChangeExistingData() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .calendarUrl(ValuePatch.modifyTo(UPDATED_CALENDAR_URL))
            .build();

        assertThatThrownBy(() -> testee().update(USER_2, inserted.publicId(), patchRequest).block())
            .isInstanceOf(BookingLinkNotFoundException.class);

        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block())
            .isEqualTo(inserted);
    }

    @Test
    default void updateShouldApplyNewValues() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .calendarUrl(ValuePatch.modifyTo(UPDATED_CALENDAR_URL))
            .duration(ValuePatch.modifyTo(UPDATED_DURATION))
            .active(ValuePatch.modifyTo(!ACTIVE))
            .availabilityRules(ValuePatch.modifyTo(UPDATED_AVAILABILITY_RULES))
            .build();
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
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .duration(ValuePatch.modifyTo(UPDATED_DURATION))
            .build();

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
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .availabilityRules(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.availabilityRules()).isEmpty();
    }

    @Test
    default void insertShouldPersistNameAndDescription() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .name("Intro call")
            .description("Book a 30-minute intro call")
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.name()).contains("Intro call");
        assertThat(created.description()).contains("Book a 30-minute intro call");

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultNameAndDescriptionToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.name()).isEmpty();
        assertThat(created.description()).isEmpty();
    }

    @Test
    default void updateShouldApplyNameAndDescription() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .name(ValuePatch.modifyTo("Updated name"))
            .description(ValuePatch.modifyTo("Updated description"))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.name()).contains("Updated name");
        assertThat(updated.description()).contains("Updated description");
    }

    @Test
    default void updateShouldAllowRemovingNameAndDescription() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .name("Intro call")
            .description("Some description")
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .name(ValuePatch.remove())
            .description(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.name()).isEmpty();
        assertThat(updated.description()).isEmpty();
    }

    @Test
    default void insertShouldPersistAutoAccept() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .autoAccept(true)
            .availabilityRules(AVAILABILITY_RULES)
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.autoAccept()).isTrue();

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultAutoAcceptToFalse() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.autoAccept()).isFalse();
    }

    @Test
    default void updateShouldApplyAutoAccept() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .autoAccept(ValuePatch.modifyTo(true))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.autoAccept()).isTrue();
    }

    @Test
    default void insertShouldPersistColor() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .color("#123456")
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.color()).contains("#123456");

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultColorToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.color()).isEmpty();
        assertThat(created.colorOrDefault()).isEqualTo(BookingLink.DEFAULT_COLOR);
    }

    @Test
    default void updateShouldApplyColor() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .color(ValuePatch.modifyTo("#abcdef"))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.color()).contains("#abcdef");
    }

    @Test
    default void updateShouldAllowRemovingColor() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .color("#123456")
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .color(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.color()).isEmpty();
    }

    @Test
    default void insertShouldPersistExtraAttendees() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .extraAttendees(ExtraAttendees.of(EXTRA_ATTENDEE_1, EXTRA_ATTENDEE_2))
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.extraAttendees()).isEqualTo(ExtraAttendees.of(EXTRA_ATTENDEE_1, EXTRA_ATTENDEE_2));

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultExtraAttendeesToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.extraAttendees()).isEqualTo(ExtraAttendees.NONE);

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found.extraAttendees()).isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    default void updateShouldApplyExtraAttendees() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .extraAttendees(ValuePatch.modifyTo(ExtraAttendees.of(EXTRA_ATTENDEE_1)))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.extraAttendees()).isEqualTo(ExtraAttendees.of(EXTRA_ATTENDEE_1));
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingExtraAttendees() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .extraAttendees(ExtraAttendees.of(EXTRA_ATTENDEE_1))
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .extraAttendees(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.extraAttendees()).isEqualTo(ExtraAttendees.NONE);
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block().extraAttendees()).isEqualTo(ExtraAttendees.NONE);
    }

    @Test
    default void updateShouldKeepExtraAttendeesWhenNotSpecified() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .extraAttendees(ExtraAttendees.of(EXTRA_ATTENDEE_1))
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .duration(ValuePatch.modifyTo(UPDATED_DURATION))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.extraAttendees()).isEqualTo(ExtraAttendees.of(EXTRA_ATTENDEE_1));
    }

    @Test
    default void insertShouldPersistLocation() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .location("Room 3")
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.location()).contains("Room 3");

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultLocationToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.location()).isEmpty();
    }

    @Test
    default void updateShouldApplyLocation() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .location(ValuePatch.modifyTo("Room 3"))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.location()).contains("Room 3");
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingLocation() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .location("Room 3")
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .location(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.location()).isEmpty();
    }

    @Test
    default void insertShouldPersistVisibility() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .visibility(EventVisibility.PRIVATE)
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.visibility()).contains(EventVisibility.PRIVATE);

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultVisibilityToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.visibility()).isEmpty();
    }

    @Test
    default void updateShouldApplyVisibility() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .visibility(ValuePatch.modifyTo(EventVisibility.PRIVATE))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.visibility()).contains(EventVisibility.PRIVATE);
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingVisibility() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .visibility(EventVisibility.PRIVATE)
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .visibility(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.visibility()).isEmpty();
    }

    @Test
    default void insertShouldPersistTransparency() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .transparency(EventTransparency.TRANSPARENT)
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.transparency()).contains(EventTransparency.TRANSPARENT);

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultTransparencyToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.transparency()).isEmpty();
    }

    @Test
    default void updateShouldApplyTransparency() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .transparency(ValuePatch.modifyTo(EventTransparency.TRANSPARENT))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.transparency()).contains(EventTransparency.TRANSPARENT);
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingTransparency() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .transparency(EventTransparency.TRANSPARENT)
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .transparency(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.transparency()).isEmpty();
    }

    @Test
    default void insertShouldPersistResources() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .resources(List.of(RESOURCE_1, RESOURCE_2))
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.resources()).containsExactly(RESOURCE_1, RESOURCE_2);

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultResourcesToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.resources()).isEmpty();

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found.resources()).isEmpty();
    }

    @Test
    default void updateShouldApplyResources() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .resources(ValuePatch.modifyTo(List.of(RESOURCE_1)))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.resources()).containsExactly(RESOURCE_1);
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingResources() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .resources(List.of(RESOURCE_1))
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .resources(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.resources()).isEmpty();
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block().resources()).isEmpty();
    }

    @Test
    default void updateShouldKeepResourcesWhenNotSpecified() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .resources(List.of(RESOURCE_1))
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .duration(ValuePatch.modifyTo(UPDATED_DURATION))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.resources()).containsExactly(RESOURCE_1);
    }

    @Test
    default void insertShouldPersistAlarm() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .alarm(ALARM)
            .build();

        BookingLink created = testee().insert(USER_1, request).block();

        assertThat(created.alarm()).contains(ALARM);

        BookingLink found = testee().findByPublicId(USER_1, created.publicId()).block();
        assertThat(found).isEqualTo(created);
    }

    @Test
    default void insertShouldDefaultAlarmToEmpty() {
        BookingLink created = testee().insert(USER_1, INSERT_REQUEST).block();

        assertThat(created.alarm()).isEmpty();
    }

    @Test
    default void updateShouldApplyAlarm() {
        BookingLink inserted = testee().insert(USER_1, INSERT_REQUEST).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .alarm(ValuePatch.modifyTo(ALARM))
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.alarm()).contains(ALARM);
        assertThat(testee().findByPublicId(USER_1, inserted.publicId()).block()).isEqualTo(updated);
    }

    @Test
    default void updateShouldAllowRemovingAlarm() {
        BookingLinkInsertRequest request = BookingLinkInsertRequest.builder()
            .calendarUrl(CALENDAR_URL)
            .eventDuration(EVENT_DURATION)
            .availabilityRules(AVAILABILITY_RULES)
            .alarm(ALARM)
            .build();
        BookingLink inserted = testee().insert(USER_1, request).block();
        BookingLinkPatchRequest patchRequest = BookingLinkPatchRequest.builder()
            .alarm(ValuePatch.remove())
            .build();

        BookingLink updated = testee().update(USER_1, inserted.publicId(), patchRequest).block();

        assertThat(updated.alarm()).isEmpty();
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
