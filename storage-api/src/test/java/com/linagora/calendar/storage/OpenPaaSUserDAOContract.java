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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.exception.UserConflictException;
import com.linagora.calendar.storage.exception.UserNotFoundException;

public interface OpenPaaSUserDAOContract {

    Username USERNAME = Username.of("user@domain.tld");
    Username USERNAME_2 = Username.of("username@domain.other.tld");

    OpenPaaSUserDAO testee();

    @Test
    default void listShouldReturnEmptyByDefault() {
        List<OpenPaaSUser> actual = testee().list().collectList().block();
        assertThat(actual).isEmpty();
    }

    @Test
    default void listShouldReturnAddedResults() {
        OpenPaaSUser actual = testee().add(USERNAME).block();

        assertThat(testee().list().collectList().block()).containsOnly(actual);
    }

    @Test
    default void retriveByUsernameShouldReturnAddedResult() {
        OpenPaaSUser actual = testee().add(USERNAME).block();

        assertThat(testee().retrieve(USERNAME).block()).isEqualTo(actual);
    }

    @Test
    default void retriveByIdShouldReturnAddedResult() {
        OpenPaaSUser actual = testee().add(USERNAME).block();

        assertThat(testee().retrieve(actual.id()).block()).isEqualTo(actual);
    }

    @Test
    default void retriveByUsernameShouldReturnEmptyByDefault() {
        assertThat(testee().retrieve(USERNAME).blockOptional()).isEmpty();
    }

    @Test
    default void retriveByIdShouldReturnEmptyByDefault() {
        assertThat(testee().retrieve(new OpenPaaSId("659387b9d486dc0046aeff21")).blockOptional()).isEmpty();
    }

    @Test
    default void addWithNameShouldWork() {
        OpenPaaSUser actual = testee().add(USERNAME, "James", "Bond").block();

        assertThat(testee().retrieve(USERNAME).block()).isEqualTo(actual);
    }

    @Test
    default void addShouldThrowWhenCalledTwice() {
        testee().add(USERNAME).block();

        assertThatThrownBy(() -> testee().add(USERNAME).block())
            .isInstanceOf(UserConflictException.class);
    }

    @Test
    default void updateShouldWork() {
        OpenPaaSUser user = testee().add(USERNAME, "James", "Bond").block();
        testee().update(user.id(), USERNAME_2, "James2", "Bond2").block();

        assertThat(testee().retrieve(user.id()).block()).isEqualTo(new OpenPaaSUser(USERNAME_2, user.id(), "James2", "Bond2"));
    }

    @Test
    default void updateShouldThrowWhenUserIdNotExist() {
        assertThatThrownBy(() -> testee().update(new OpenPaaSId("67f8d26905faf173b5e693a0"), USERNAME_2, "James2", "Bond2").block())
            .isInstanceOf(UserNotFoundException.class);

    }

    @Test
    default void updateShouldThrowWhenUsernameDuplicate() {
        testee().add(USERNAME, "James", "Bond").block();
        OpenPaaSUser user2 = testee().add(USERNAME_2, "James2", "Bond2").block();
        assertThatThrownBy(() -> testee().update(user2.id(), USERNAME, "James2", "Bond2").block())
            .isInstanceOf(UserConflictException.class);

    }

    @Test
    default void deleteShouldRemoveUser() {
        testee().add(USERNAME).block();
        testee().delete(USERNAME).block();

        assertThat(testee().retrieve(USERNAME).blockOptional()).isEmpty();
    }

    @Test
    default void deleteShouldNotThrowWhenUserDoesNotExist() {
        testee().delete(USERNAME).block(); // Should not throw
    }

    @Test
    default void deleteShouldNotAffectOtherUsers() {
        testee().add(USERNAME).block();
        testee().add(USERNAME_2).block();
        testee().delete(USERNAME).block();

        assertThat(testee().retrieve(USERNAME_2).blockOptional()).isPresent();
    }

    @Test
    default void listShouldBeMultiValued() {
        testee().add(USERNAME).block();
        testee().add(USERNAME_2).block();

        assertThat(testee().list().map(OpenPaaSUser::username).collectList().block()).containsOnly(USERNAME, USERNAME_2);
    }

    @Test
    default void searchShouldReturnEmptyWhenNoUserMatches() {
        Domain domain = Domain.of("domain.tld");
        testee().add(USERNAME, "James", "Bond").block();

        List<OpenPaaSUser> result = testee().search(domain, "nonexistent", 10).collectList().block();
        assertThat(result).isEmpty();
    }

    @Test
    default void searchShouldReturnUserWhenMatchingEmail() {
        Domain domain = Domain.of("domain.tld");
        OpenPaaSUser user = testee().add(USERNAME, "James", "Bond").block();

        List<OpenPaaSUser> result = testee().search(domain, "user@", 10).collectList().block();
        assertThat(result).contains(user);
    }

    @Test
    default void searchShouldReturnUserWhenMatchingFirstnameOrLastnameCaseInsensitive() {
        Domain domain = Domain.of("domain.tld");
        OpenPaaSUser user = testee().add(USERNAME, "Naruto", "Uzumaki").block();

        assertThat(testee().search(domain, "naru", 10).collectList().block()).contains(user);
        assertThat(testee().search(domain, "UZU", 10).collectList().block()).contains(user);
    }

    @Test
    default void searchShouldRespectLimit() {
        Domain domain = Domain.of("domain.tld");
        testee().add(USERNAME, "User", "One").block();
        testee().add(Username.of("other@domain.tld"), "User", "Two").block();

        List<OpenPaaSUser> result = testee().search(domain, "user", 1).collectList().block();
        assertThat(result).hasSize(1);
    }

    @Test
    default void searchShouldNotReturnUsersFromOtherDomains() {
        Domain domain1 = Domain.of("domain.tld");
        Domain domain2 = Domain.of("other.tld");

        OpenPaaSUser user1 = testee().add(USERNAME, "Same", "Name").block();
        OpenPaaSUser user2 = testee().add(USERNAME_2, "Same", "Name").block();

        List<OpenPaaSUser> result = testee().search(domain1, "Same", 10).collectList().block();
        assertThat(result).contains(user1).doesNotContain(user2);
    }

}
