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

package com.linagora.calendar.dav;

import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import javax.net.ssl.SSLException;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.dav.CalDavClient.NewCalendar;
import com.linagora.calendar.dav.CalDavClient.PublicRight;
import com.linagora.calendar.dav.dto.SubscribedCalendarRequest;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.model.ResourceAdministrator;
import com.linagora.calendar.storage.model.ResourceId;
import com.linagora.calendar.storage.mongodb.MongoDBResourceDAO;

class CalendarSearchSourceResolverTest {
    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    private static DavTestHelper davTestHelper;

    private CalDavClient calDavClient;
    private MongoDBResourceDAO resourceDAO;
    private CalendarSearchSourceResolver testee;

    @BeforeAll
    static void setUp() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @BeforeEach
    void setupEach() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        resourceDAO = new MongoDBResourceDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), Clock.systemUTC());
        testee = new CalendarSearchSourceResolver(calDavClient);
    }

    @Test
    void resolveShouldFilterCalendarWithoutReadAccess() {
        // Given one readable calendar and one not readable calendar.
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        OpenPaaSUser other = sabreDavExtension.newTestUser();
        CalendarURL notReadableCalendar = createCalendar(other, "not-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);
        CalendarURL readableCalendar = CalendarURL.from(requester.id());

        // When resolving the search sources.
        List<CalendarURL> result = testee.resolve(requester, List.of(readableCalendar, notReadableCalendar)).block();

        // Then the not readable calendar is filtered out.
        assertThat(result)
            .describedAs("Resolver should keep calendars readable by requester and filter calendars without read access")
            .containsExactly(readableCalendar);
    }

    @Test
    void resolveShouldTranslateSubscribedMirrorCalendarToSourceCalendar() {
        // Given subscribed mirror A/B points to source D/E.
        OpenPaaSUser sourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();

        String sourceCalendarId = "source-" + UUID.randomUUID();
        CalendarURL sourceCalendar = createCalendar(sourceUser, sourceCalendarId, PublicRight.READ);

        String subscribedCalendarId = "subscribed-" + UUID.randomUUID();
        CalendarURL requestedCalendar = davTestHelper.subscribeToSharedCalendar(subscriber, SubscribedCalendarRequest.builder()
            .id(subscribedCalendarId)
            .sourceUserId(sourceUser.id().value())
            .sourceCalendarId(sourceCalendarId)
            .name("Subscribed source")
            .color("#123456")
            .readOnly(true)
            .build());

        // When resolving A/B.
        List<CalendarURL> result = testee.resolve(subscriber, List.of(requestedCalendar)).block();

        // Then D/E is returned.
        assertThat(result)
            .describedAs("Resolver should translate a subscribed mirror calendar to its source calendar")
            .containsExactly(sourceCalendar);
    }

    @Test
    void resolveShouldKeepSubscribedSourceCalendarWhenRequestedDirectly() {
        // Given subscriber has a mirror A/B for source D/E.
        OpenPaaSUser sourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser subscriber = sabreDavExtension.newTestUser();

        String sourceCalendarId = "source-" + UUID.randomUUID();
        CalendarURL sourceCalendar = createCalendar(sourceUser, sourceCalendarId, PublicRight.READ);

        davTestHelper.subscribeToSharedCalendar(subscriber, SubscribedCalendarRequest.builder()
            .id("subscribed-" + UUID.randomUUID())
            .sourceUserId(sourceUser.id().value())
            .sourceCalendarId(sourceCalendarId)
            .name("Subscribed source")
            .color("#123456")
            .readOnly(true)
            .build());

        // When resolving D/E directly.
        List<CalendarURL> result = testee.resolve(subscriber, List.of(sourceCalendar)).block();

        // Then D/E is kept.
        assertThat(result)
            .describedAs("Resolver should keep the source calendar when it is requested directly")
            .containsExactly(sourceCalendar);
    }

    @Test
    void resolveShouldTranslateDelegatedMirrorCalendarToSourceCalendar() {
        // Given delegated mirror A/B points to source D/E.
        OpenPaaSUser sourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        String sourceCalendarId = "delegated-" + UUID.randomUUID();
        CalendarURL sourceCalendar = createCalendar(sourceUser, sourceCalendarId, PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(sourceUser, sourceCalendar, delegate, "dav:read");
        CalendarURL requestedCalendar = calDavClient.findUserCalendars(delegate.username(), delegate.id())
            .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(delegate.id())))
            .next()
            .blockOptional()
            .orElseThrow(() -> new AssertionError("No mirror calendar found"));

        // When resolving A/B.
        List<CalendarURL> result = testee.resolve(delegate, List.of(requestedCalendar)).block();

        // Then D/E is returned.
        assertThat(result)
            .describedAs("Resolver should translate a delegated mirror calendar to its source calendar")
            .containsExactly(sourceCalendar);
    }

    @Test
    void resolveShouldKeepDelegatedSourceCalendarWhenRequestedDirectly() {
        // Given source D/E is directly readable and also has a delegated mirror.
        OpenPaaSUser sourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        String sourceCalendarId = "delegated-" + UUID.randomUUID();
        CalendarURL sourceCalendar = createCalendar(sourceUser, sourceCalendarId, PublicRight.READ);
        davTestHelper.grantDelegation(sourceUser, sourceCalendar, delegate, "dav:read");

        // When resolving D/E.
        List<CalendarURL> result = testee.resolve(delegate, List.of(sourceCalendar)).block();

        // Then D/E is kept.
        assertThat(result)
            .describedAs("Resolver should keep a directly readable delegated source calendar when requested directly")
            .containsExactly(sourceCalendar);
    }

    @Test
    void resolveShouldFilterDelegatedMirrorCalendarWhenRequesterIsNotDelegate() {
        // Given delegated mirror A/B belongs to a different delegate.
        OpenPaaSUser sourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        String sourceCalendarId = "delegated-" + UUID.randomUUID();
        CalendarURL sourceCalendar = createCalendar(sourceUser, sourceCalendarId, PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(sourceUser, sourceCalendar, delegate, "dav:read");
        CalendarURL delegatedMirrorCalendar = calDavClient.findUserCalendars(delegate.username(), delegate.id())
            .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(delegate.id())))
            .next()
            .blockOptional()
            .orElseThrow(() -> new AssertionError("No mirror calendar found"));

        // When another user resolves A/B.
        List<CalendarURL> result = testee.resolve(requester, List.of(delegatedMirrorCalendar)).block();

        // Then the delegated mirror is filtered.
        assertThat(result)
            .describedAs("Resolver should filter a delegated mirror calendar when requester is not the delegate")
            .isEmpty();
    }

    @Test
    void resolveShouldKeepMultipleReadableCalendarsAndFilterUnreadableCalendars() {
        // Given several readable calendars and one not readable calendar.
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        OpenPaaSUser user1 = sabreDavExtension.newTestUser();
        OpenPaaSUser user2 = sabreDavExtension.newTestUser();
        OpenPaaSUser user3 = sabreDavExtension.newTestUser();

        CalendarURL ownCalendar = CalendarURL.from(requester.id());
        CalendarURL subscribedSourceCalendar = createCalendar(user1, "subscribed-readable-" + UUID.randomUUID(), PublicRight.READ);
        davTestHelper.subscribeToSharedCalendar(requester, SubscribedCalendarRequest.builder()
            .id("subscribed-" + UUID.randomUUID())
            .sourceUserId(user1.id().value())
            .sourceCalendarId(subscribedSourceCalendar.calendarId().value())
            .name("Subscribed readable source")
            .color("#123456")
            .readOnly(true)
            .build());

        CalendarURL delegatedSourceCalendar = createCalendar(user2, "delegated-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(user2, delegatedSourceCalendar, requester, "dav:read");

        CalendarURL notReadableCalendar = createCalendar(user3, "not-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);

        // When resolving all requested calendars.
        List<CalendarURL> result = testee.resolve(requester,
            List.of(ownCalendar, subscribedSourceCalendar, notReadableCalendar, delegatedSourceCalendar)).block();

        // Then all readable calendars are kept and the not readable calendar is filtered.
        assertThat(result)
            .describedAs("Resolver should keep all readable calendars in request order and filter unreadable calendars")
            .containsExactly(ownCalendar, subscribedSourceCalendar, delegatedSourceCalendar);
    }

    @Test
    void resolveShouldKeepResourceCalendarWhenRequesterIsResourceAdministrator() {
        // Given requester is administrator of resource calendar R/R.
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .getDomain()
            .block();
        ResourceId resourceId = createResource(domain, requester, List.of(requester));
        CalendarURL resourceCalendar = CalendarURL.from(resourceId.asOpenPaaSId());
        calDavClient.grantReadWriteRights(domain.id(), resourceId, List.of(requester.username())).block();

        // When resolving R/R.
        List<CalendarURL> result = testee.resolve(requester, List.of(resourceCalendar)).block();

        // Then R/R is kept.
        assertThat(result)
            .describedAs("Resolver should keep a resource calendar readable by its administrator")
            .containsExactly(resourceCalendar);
    }

    @Test
    void resolveShouldTranslateSubscribedResourceCalendarToResourceCalendar() {
        // Given requester subscribed to resource calendar R/R.
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        OpenPaaSUser resourceCreator = sabreDavExtension.newTestUser();
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .getDomain()
            .block();
        ResourceId resourceId = createResource(domain, resourceCreator, List.of());
        CalendarURL resourceCalendar = CalendarURL.from(resourceId.asOpenPaaSId());
        CalendarURL subscribedCalendar = davTestHelper.subscribeToSharedCalendar(requester, SubscribedCalendarRequest.builder()
            .id("subscribed-resource-" + UUID.randomUUID())
            .sourceUserId(resourceId.value())
            .sourceCalendarId(resourceId.value())
            .name("Subscribed resource")
            .color("#123456")
            .readOnly(true)
            .build());

        // When resolving subscribed mirror A/B.
        List<CalendarURL> result = testee.resolve(requester, List.of(subscribedCalendar)).block();

        // Then R/R is returned.
        assertThat(result)
            .describedAs("Resolver should translate a subscribed resource calendar to its source resource calendar")
            .containsExactly(resourceCalendar);
    }

    @Test
    void resolveShouldFilterResourceCalendarFromAnotherDomain() {
        // Given resource calendar R/R belongs to another domain.
        OpenPaaSUser requester = sabreDavExtension.newTestUser();
        OpenPaaSDomain foreignDomain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createDomainIfAbsent(Domain.of("resource-" + UUID.randomUUID() + ".tld"))
            .block();
        OpenPaaSUser foreignAdmin = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .createUser(Username.fromLocalPartWithDomain("user-" + UUID.randomUUID(), foreignDomain.domain()))
            .block();
        ResourceId resourceId = createResource(foreignDomain, foreignAdmin, List.of(foreignAdmin));
        CalendarURL resourceCalendar = CalendarURL.from(resourceId.asOpenPaaSId());
        calDavClient.grantReadWriteRights(foreignDomain.id(), resourceId, List.of(foreignAdmin.username())).block();

        // When resolving R/R as a requester from another domain.
        List<CalendarURL> result = testee.resolve(requester, List.of(resourceCalendar)).block();

        // Then the resource calendar is filtered.
        assertThat(result)
            .describedAs("Resolver should filter resource calendars from another domain")
            .isEmpty();
    }

    private CalendarURL createCalendar(OpenPaaSUser calendarUser, String calendarId, PublicRight publicRight) {
        calDavClient.createNewCalendar(calendarUser.username(), calendarUser.id(), new NewCalendar(
            calendarId,
            "Calendar " + calendarId,
            "#123456",
            "Calendar search scope test")).block();

        CalendarURL calendarURL = new CalendarURL(calendarUser.id(), new OpenPaaSId(calendarId));
        calDavClient.updateCalendarAcl(calendarUser.username(), calendarURL, publicRight).block();
        return calendarURL;
    }

    private ResourceId createResource(OpenPaaSDomain domain, OpenPaaSUser creator, List<OpenPaaSUser> administrators) {
        ResourceInsertRequest insertRequest = new ResourceInsertRequest(
            administrators.stream()
                .map(administrator -> new ResourceAdministrator(administrator.id(), "user"))
                .toList(),
            creator.id(),
            "Resource calendar search source resolver test",
            domain.id(),
            "projector",
            "Resource " + UUID.randomUUID());

        return resourceDAO.insert(insertRequest).block();
    }
}
