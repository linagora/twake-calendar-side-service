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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

class BookingLinkExtraAttendeeResolverTest {

    private static final Username OWNER = Username.of("owner@linagora.com");
    private static final Username EXTRA_ATTENDEE = Username.of("extra@linagora.com");
    private static final OpenPaaSId UNKNOWN_ID = new OpenPaaSId("659387b9d486dc0046aeffff");

    private MemoryOpenPaaSUserDAO userDAO;
    private BookingLinkExtraAttendeeResolver testee;

    @BeforeEach
    void setUp() {
        userDAO = new MemoryOpenPaaSUserDAO();
        testee = new BookingLinkExtraAttendeeResolver(userDAO);
    }

    @Test
    void validateShouldAcceptRegisteredUsers() {
        userDAO.add(OWNER).block();
        OpenPaaSUser extraAttendee = userDAO.add(EXTRA_ATTENDEE).block();

        assertThatCode(() -> testee.validate(OWNER, List.of(extraAttendee.id())).block())
            .doesNotThrowAnyException();
    }

    @Test
    void validateShouldRejectUnknownUser() {
        userDAO.add(OWNER).block();

        assertThatThrownBy(() -> testee.validate(OWNER, List.of(UNKNOWN_ID)).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(UNKNOWN_ID.value());
    }

    @Test
    void validateShouldRejectTheOwner() {
        OpenPaaSUser owner = userDAO.add(OWNER).block();

        assertThatThrownBy(() -> testee.validate(OWNER, List.of(owner.id())).block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must not contain the booking link owner");
    }

    @Test
    void validateShouldRejectUnknownUserWhenOwnerIsNotRegistered() {
        assertThatThrownBy(() -> testee.validate(OWNER, List.of(UNKNOWN_ID)).block())
            .describedAs("an unregistered owner must not bypass the extra attendees validation")
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(UNKNOWN_ID.value());
    }

    @Test
    void validateShouldAcceptEmptyExtraAttendees() {
        assertThatCode(() -> testee.validate(OWNER, List.of()).block())
            .doesNotThrowAnyException();
    }

    @Test
    void resolveShouldFailWhenUserDoesNotExist() {
        assertThatThrownBy(() -> testee.resolve(List.of(UNKNOWN_ID)).collectList().block())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(UNKNOWN_ID.value());
    }

    @Test
    void resolveExistingShouldSkipUsersThatDoNotExist() {
        OpenPaaSUser extraAttendee = userDAO.add(EXTRA_ATTENDEE).block();

        assertThat(testee.resolveExisting(List.of(UNKNOWN_ID, extraAttendee.id())).collectList().block())
            .containsExactly(extraAttendee);
    }
}
