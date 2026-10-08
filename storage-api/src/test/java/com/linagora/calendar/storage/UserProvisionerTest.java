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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.user.api.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.UserNameResolver.UserNames;

import reactor.core.publisher.Mono;

class UserProvisionerTest {
    private static final Domain DOMAIN = Domain.of("linagora.com");
    private static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);
    private static final Username EXTERNAL = Username.of("bob@external.com");

    private MemoryOpenPaaSUserDAO userDAO;
    private UsersRepository usersRepository;
    private UserProvisioner testee;

    @BeforeEach
    void setUp() {
        MemoryOpenPaaSDomainDAO domainDAO = new MemoryOpenPaaSDomainDAO();
        domainDAO.add(DOMAIN).block();
        userDAO = new MemoryOpenPaaSUserDAO();
        usersRepository = mock(UsersRepository.class);
        when(usersRepository.containsReactive(any(Username.class))).thenReturn(Mono.just(false));
        UserNameResolver userNameResolver = username -> Mono.just(Optional.of(new UserNames("Bob", "Dylan")));

        testee = new UserProvisioner(userDAO, domainDAO, usersRepository, userNameResolver);
    }

    @Test
    void shouldProvisionUserKnownByUsersRepository() {
        when(usersRepository.containsReactive(BOB)).thenReturn(Mono.just(true));

        OpenPaaSUser provisioned = testee.provisionIfExists(BOB).block();

        assertThat(userDAO.retrieve(BOB).block())
            .isEqualTo(provisioned)
            .satisfies(user -> {
                assertThat(user.firstname()).isEqualTo("Bob");
                assertThat(user.lastname()).isEqualTo("Dylan");
            });
    }

    @Test
    void shouldNotProvisionUserUnknownByUsersRepository() {
        assertThat(testee.provisionIfExists(BOB).blockOptional()).isEmpty();
        assertThat(userDAO.retrieve(BOB).blockOptional()).isEmpty();
    }

    @Test
    void shouldNotQueryUsersRepositoryForExternalDomains() {
        assertThat(testee.provisionIfExists(EXTERNAL).blockOptional()).isEmpty();

        verify(usersRepository, never()).containsReactive(any(Username.class));
    }

    @Test
    void shouldReturnExistingUserUponConcurrentProvisioning() {
        when(usersRepository.containsReactive(BOB)).thenReturn(Mono.just(true));
        OpenPaaSUser existing = userDAO.add(BOB).block();

        assertThat(testee.provisionIfExists(BOB).block()).isEqualTo(existing);
    }
}
