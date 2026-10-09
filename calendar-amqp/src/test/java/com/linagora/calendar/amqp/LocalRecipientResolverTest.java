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

package com.linagora.calendar.amqp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.Optional;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.user.api.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.amqp.LocalRecipientResolver.ResolvedRecipient;
import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.MemoryResourceDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.UserNameResolver;
import com.linagora.calendar.storage.UserProvisioner;
import com.linagora.calendar.storage.model.ResourceId;

import reactor.core.publisher.Mono;

class LocalRecipientResolverTest {
    private static final Domain DOMAIN = Domain.of("linagora.com");
    private static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);

    private MemoryOpenPaaSUserDAO userDAO;
    private MemoryResourceDAO resourceDAO;
    private UsersRepository usersRepository;
    private OpenPaaSDomain domain;
    private LocalRecipientResolver testee;

    @BeforeEach
    void setUp() {
        MemoryOpenPaaSDomainDAO domainDAO = new MemoryOpenPaaSDomainDAO();
        domain = domainDAO.add(DOMAIN).block();
        userDAO = new MemoryOpenPaaSUserDAO();
        resourceDAO = new MemoryResourceDAO(Clock.systemUTC());
        usersRepository = mock(UsersRepository.class);
        when(usersRepository.containsReactive(any(Username.class))).thenReturn(Mono.just(false));

        testee = new LocalRecipientResolver(userDAO, resourceDAO, domainDAO,
            new UserProvisioner(userDAO, domainDAO, usersRepository, new UserNameResolver.Noop()));
    }

    @Test
    void shouldResolveKnownUser() {
        OpenPaaSUser bob = userDAO.add(BOB).block();

        assertThat(testee.resolve(BOB).block())
            .contains(new ResolvedRecipient.LocalUser(bob.id()));
    }

    @Test
    void shouldResolveResource() {
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(new OpenPaaSId("creator"),
            "description", domain.id(), "icon.png", "room")).block();

        assertThat(testee.resolve(Username.fromLocalPartWithDomain(resourceId.value(), DOMAIN)).block())
            .contains(new ResolvedRecipient.LocalResource(resourceId.asOpenPaaSId()));
    }

    @Test
    void shouldProvisionUserOnlyKnownByUsersRepository() {
        when(usersRepository.containsReactive(BOB)).thenReturn(Mono.just(true));

        Optional<ResolvedRecipient> resolved = testee.resolve(BOB).block();

        OpenPaaSUser provisioned = userDAO.retrieve(BOB).block();
        assertThat(resolved).contains(new ResolvedRecipient.LocalUser(provisioned.id()));
    }

    @Test
    void shouldNotProvisionResourceAsUser() {
        ResourceId resourceId = resourceDAO.insert(new ResourceInsertRequest(new OpenPaaSId("creator"),
            "description", domain.id(), "icon.png", "room")).block();
        Username resourceUsername = Username.fromLocalPartWithDomain(resourceId.value(), DOMAIN);
        when(usersRepository.containsReactive(resourceUsername)).thenReturn(Mono.just(true));

        testee.resolve(resourceUsername).block();

        assertThat(userDAO.retrieve(resourceUsername).blockOptional()).isEmpty();
    }

    @Test
    void shouldNotResolveUnknownRecipient() {
        assertThat(testee.resolve(BOB).block()).isEmpty();
        assertThat(userDAO.retrieve(BOB).blockOptional()).isEmpty();
    }
}
