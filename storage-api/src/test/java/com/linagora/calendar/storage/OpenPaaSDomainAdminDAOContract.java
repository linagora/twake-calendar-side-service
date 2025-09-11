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

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.List;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.Test;

public interface OpenPaaSDomainAdminDAOContract {

    Domain DOMAIN = Domain.of("domain.tld");

    OpenPaaSDomainDAO domainDAO();

    OpenPaaSDomainAdminDAO testee();

    @Test
    default void listAdminsShouldReturnEmptyWhenEmpty() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();

        assertThat(admins).isEmpty();
    }

    @Test
    default void addAdminsShouldAddNewAdmin() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();
        OpenPaaSId userId = new OpenPaaSId("659387b9d486dc0046aeff21");

        testee().addAdmins(domain.id(), List.of(userId)).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();
        assertThat(admins)
            .extracting(DomainAdministrator::userId)
            .containsExactly(userId);
    }

    @Test
    default void addAdminsShouldNotDuplicate() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();
        OpenPaaSId userId = new OpenPaaSId("659387b9d486dc0046aeff21");

        testee().addAdmins(domain.id(), List.of(userId)).block();
        testee().addAdmins(domain.id(), List.of(userId)).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();
        assertThat(admins)
            .extracting(DomainAdministrator::userId)
            .containsExactly(userId);
    }

    @Test
    default void revokeAdminShouldRemoveAdmin() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();
        OpenPaaSId userId = new OpenPaaSId("659387b9d486dc0046aeff21");

        testee().addAdmins(domain.id(), List.of(userId)).block();
        testee().revokeAdmin(domain.id(), userId).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();
        assertThat(admins).isEmpty();
    }

    @Test
    default void addAdminsShouldBeMultiValued() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();
        OpenPaaSId user1 = new OpenPaaSId("659387b9d486dc0046aeff21");
        OpenPaaSId user2 = new OpenPaaSId("659387b9d486dc0046aeff22");

        testee().addAdmins(domain.id(), List.of(user1, user2)).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();
        assertThat(admins)
            .extracting(DomainAdministrator::userId)
            .containsExactlyInAnyOrder(user1, user2);
    }

    @Test
    default void revokeAdminShouldBeIdempotent() {
        OpenPaaSDomain domain = domainDAO().add(DOMAIN).block();
        OpenPaaSId userId = new OpenPaaSId("659387b9d486dc0046aeff21");
        testee().addAdmins(domain.id(), List.of(userId)).block();

        testee().revokeAdmin(domain.id(), userId).block();

        // Second revoke should not fail
        testee().revokeAdmin(domain.id(), userId).block();

        List<DomainAdministrator> admins = testee().listAdmins(domain.id()).collectList().block();
        assertThat(admins).isEmpty();
    }

}
