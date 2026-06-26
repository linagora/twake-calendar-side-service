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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.apache.james.core.Domain;
import org.apache.james.utils.UpdatableTickingClock;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.model.TeamCalendar;
import com.linagora.calendar.storage.model.TeamCalendarId;

public interface TeamCalendarRepositoryContract {
    OpenPaaSId DOMAIN_ID = new OpenPaaSId("659387b9d486dc0046aeff96");
    OpenPaaSId OTHER_DOMAIN_OPENPAAS_ID = new OpenPaaSId("659387b9d486dc0046aeff97");
    Domain DOMAIN_NAME = Domain.of("linagora.com");
    Domain OTHER_DOMAIN_NAME = Domain.of("example.com");
    OpenPaaSDomain DOMAIN = new OpenPaaSDomain(DOMAIN_NAME, DOMAIN_ID);
    OpenPaaSDomain OTHER_DOMAIN = new OpenPaaSDomain(OTHER_DOMAIN_NAME, OTHER_DOMAIN_OPENPAAS_ID);
    String SALES = "sales";
    String SUPPORT = "support";

    TeamCalendarRepository testee();

    UpdatableTickingClock clock();

    TeamCalendarId generateTeamCalendarId();

    default TeamCalendarInsertRequest teamCalendarInsertRequest(OpenPaaSDomain domain, String name, String displayName) {
        return new TeamCalendarInsertRequest(domain, name, displayName);
    }

    default TeamCalendar createTeamCalendar(OpenPaaSDomain domain, String name, String displayName) {
        testee().create(teamCalendarInsertRequest(domain, name, displayName)).block();

        return testee().retrieve(domain.id(), name).blockFirst();
    }

    @Test
    default void createShouldStoreTeamCalendar() {
        Instant now = clock().instant();

        testee().create(teamCalendarInsertRequest(DOMAIN, SALES, "Sales Team")).block();

        TeamCalendar created = testee().retrieve(DOMAIN_ID, SALES).blockFirst();
        TeamCalendar expected = new TeamCalendar(created.id(), DOMAIN, SALES, "Sales Team", now, now);

        assertThat(created.id().value()).isNotBlank();
        assertThat(created).isEqualTo(expected);
    }

    @Test
    default void createShouldAllowSameDomainAndNameWithDifferentIds() {
        testee().create(teamCalendarInsertRequest(DOMAIN, SALES, "Sales Team")).block();
        testee().create(teamCalendarInsertRequest(DOMAIN, SALES, "Other Sales Team")).block();

        List<TeamCalendar> result = testee().retrieve(DOMAIN_ID, SALES).collectList().block();

        assertThat(result)
            .hasSize(2)
            .extracting(TeamCalendar::displayName)
            .containsExactlyInAnyOrder("Sales Team", "Other Sales Team");
        assertThat(result)
            .extracting(TeamCalendar::id)
            .doesNotHaveDuplicates();
    }

    @Test
    default void retrieveByIdShouldReturnEmptyWhenMissing() {
        assertThat(testee().retrieve(generateTeamCalendarId()).blockOptional())
            .isEmpty();
    }

    @Test
    default void retrieveByDomainAndNameShouldReturnMatchingTeamCalendars() {
        TeamCalendar sales = createTeamCalendar(DOMAIN, SALES, "Sales Team");
        createTeamCalendar(DOMAIN, SUPPORT, "Support Team");
        createTeamCalendar(OTHER_DOMAIN, SALES, "External Sales");

        List<TeamCalendar> result = testee().retrieve(DOMAIN_ID, SALES).collectList().block();

        assertThat(result)
            .containsExactly(sales);
    }

    @Test
    default void retrieveByDomainAndNameShouldReturnEmptyWhenNoneMatches() {
        assertThat(testee().retrieve(DOMAIN_ID, SALES).collectList().block())
            .isEmpty();
    }

    @Test
    default void existsShouldReturnTrueWhenTeamCalendarExists() {
        createTeamCalendar(DOMAIN, SALES, "Sales Team");

        assertThat(testee().exists(DOMAIN_ID, SALES).block())
            .isTrue();
    }

    @Test
    default void existsShouldReturnFalseWhenNameDoesNotMatch() {
        createTeamCalendar(DOMAIN, SALES, "Sales Team");

        assertThat(testee().exists(DOMAIN_ID, SUPPORT).block())
            .isFalse();
    }

    @Test
    default void existsShouldReturnFalseWhenDomainDoesNotMatch() {
        createTeamCalendar(DOMAIN, SALES, "Sales Team");

        assertThat(testee().exists(OTHER_DOMAIN_OPENPAAS_ID, SALES).block())
            .isFalse();
    }

    @Test
    default void listByDomainShouldReturnOnlyTeamCalendarsOfDomain() {
        TeamCalendar sales = createTeamCalendar(DOMAIN, SALES, "Sales Team");
        TeamCalendar support = createTeamCalendar(DOMAIN, SUPPORT, "Support Team");
        createTeamCalendar(OTHER_DOMAIN, SALES, "External Sales");

        List<TeamCalendar> result = testee().listByDomain(DOMAIN_ID).collectList().block();

        assertThat(result)
            .containsExactlyInAnyOrder(sales, support);
    }

    @Test
    default void listByDomainShouldReturnEmptyWhenNoneMatches() {
        createTeamCalendar(OTHER_DOMAIN, SALES, "External Sales");

        assertThat(testee().listByDomain(DOMAIN_ID).collectList().block())
            .isEmpty();
    }

    @Test
    default void updateDisplayNameShouldUpdateDisplayNameAndUpdatedTimestampOnly() {
        TeamCalendar teamCalendar = createTeamCalendar(DOMAIN, SALES, "Sales Team");
        Instant newUpdateTime = teamCalendar.updated().plusSeconds(1);
        clock().setInstant(newUpdateTime);

        TeamCalendar updated = testee().updateDisplayName(teamCalendar.id(), "Global Sales").block();
        TeamCalendar expected = new TeamCalendar(teamCalendar.id(), DOMAIN, SALES, "Global Sales",
            teamCalendar.creation(), newUpdateTime);

        assertThat(updated).isEqualTo(expected);
    }

    @Test
    default void updateDisplayNameShouldStoreUpdatedTeamCalendar() {
        TeamCalendar teamCalendar = createTeamCalendar(DOMAIN, SALES, "Sales Team");

        TeamCalendar updated = testee().updateDisplayName(teamCalendar.id(), "Global Sales").block();

        assertThat(testee().retrieve(teamCalendar.id()).block())
            .isEqualTo(updated);
    }

    @Test
    default void updateDisplayNameShouldFailWhenTeamCalendarDoesNotExist() {
        assertThatThrownBy(() -> testee().updateDisplayName(generateTeamCalendarId(), "Global Sales").block())
            .isInstanceOf(TeamCalendarNotFoundException.class);
    }

    @Test
    default void deleteShouldRemoveTeamCalendar() {
        TeamCalendar teamCalendar = createTeamCalendar(DOMAIN, SALES, "Sales Team");

        testee().delete(teamCalendar.id()).block();

        assertThat(testee().retrieve(teamCalendar.id()).blockOptional())
            .isEmpty();
    }

    @Test
    default void deleteShouldBeIdempotentWhenTeamCalendarDoesNotExist() {
        testee().delete(generateTeamCalendarId()).block();

        assertThatCode(() -> testee().delete(generateTeamCalendarId()).block())
            .doesNotThrowAnyException();

        assertThat(testee().retrieve(generateTeamCalendarId()).blockOptional())
            .isEmpty();
    }
}
