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

import static java.util.Map.entry;
import static com.linagora.calendar.storage.TestFixture.TECHNICAL_TOKEN_SERVICE_TESTING;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.Map;
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
    static SabreDavExtension sabreDavExtension = SabreDavExtension.shared();

    private static DavTestHelper davTestHelper;

    private CalDavClient calDavClient;
    private MongoDBResourceDAO resourceDAO;
    private CalendarSearchSourceResolver testee;
    private OpenPaaSUser requester;
    private OpenPaaSUser sourceUser;

    @BeforeAll
    static void setUp() throws SSLException {
        davTestHelper = new DavTestHelper(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
    }

    @BeforeEach
    void setupEach() throws SSLException {
        calDavClient = new CalDavClient(sabreDavExtension.dockerSabreDavSetup().davConfiguration(), TECHNICAL_TOKEN_SERVICE_TESTING);
        resourceDAO = new MongoDBResourceDAO(sabreDavExtension.dockerSabreDavSetup().getMongoDB(), Clock.systemUTC());
        testee = new CalendarSearchSourceResolver(calDavClient);
        requester = sabreDavExtension.newTestUser();
        sourceUser = sabreDavExtension.newTestUser();
    }

    @Test
    void resolveShouldFilterCalendarWithoutReadAccess() {
        // Given one readable calendar and one not readable calendar.
        CalendarURL notReadableCalendar = createCalendar(sourceUser, "not-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);
        CalendarURL readableCalendar = CalendarURL.from(requester.id());

        // When resolving the search sources.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(readableCalendar, notReadableCalendar)).block();

        // Then the not readable calendar is filtered out.
        assertThat(result)
            .describedAs("Resolver should keep calendars readable by requester and filter calendars without read access")
            .containsExactly(entry(readableCalendar, readableCalendar));
    }

    @Test
    void resolveShouldTranslateSubscribedMirrorCalendarToSourceCalendar() {
        // Given subscribed mirror A/B points to source D/E.
        CalendarURL sourceCalendar = createSubscribedSourceCalendar();
        CalendarURL requestedCalendar = findMirrorCalendar(requester);

        // When resolving A/B.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(requestedCalendar)).block();

        // Then D/E is returned.
        assertThat(result)
            .describedAs("Resolver should translate a subscribed mirror calendar to its source calendar")
            .containsExactly(entry(requestedCalendar, sourceCalendar));
    }

    @Test
    void resolveShouldKeepSubscribedSourceCalendarWhenRequestedDirectly() {
        // Given subscriber has a mirror A/B for source D/E.
        CalendarURL sourceCalendar = createSubscribedSourceCalendar();

        // When resolving D/E directly.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(sourceCalendar)).block();

        // Then D/E is kept.
        assertThat(result)
            .describedAs("Resolver should keep the source calendar when it is requested directly")
            .containsExactly(entry(sourceCalendar, sourceCalendar));
    }

    @Test
    void resolveShouldTranslateDelegatedMirrorCalendarToSourceCalendar() {
        // Given delegated mirror A/B points to source D/E.
        CalendarURL sourceCalendar = createDelegatedSourceCalendar(requester, PublicRight.HIDE_ALL_EVENT);
        CalendarURL requestedCalendar = findMirrorCalendar(requester);

        // When resolving A/B.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(requestedCalendar)).block();

        // Then D/E is returned.
        assertThat(result)
            .describedAs("Resolver should translate a delegated mirror calendar to its source calendar")
            .containsExactly(entry(requestedCalendar, sourceCalendar));
    }

    @Test
    void resolveShouldKeepDelegatedSourceCalendarWhenRequestedDirectly() {
        // Given source D/E is directly readable and also has a delegated mirror.
        CalendarURL sourceCalendar = createDelegatedSourceCalendar(requester, PublicRight.READ);

        // When resolving D/E.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(sourceCalendar)).block();

        // Then D/E is kept.
        assertThat(result)
            .describedAs("Resolver should keep a directly readable delegated source calendar when requested directly")
            .containsExactly(entry(sourceCalendar, sourceCalendar));
    }

    @Test
    void resolveShouldFilterDelegatedMirrorCalendarWhenRequesterIsNotDelegate() {
        // Given delegated mirror A/B belongs to a different delegate.
        OpenPaaSUser delegate = sabreDavExtension.newTestUser();
        createDelegatedSourceCalendar(delegate, PublicRight.HIDE_ALL_EVENT);
        CalendarURL delegatedMirrorCalendar = findMirrorCalendar(delegate);

        // When another user resolves A/B.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(delegatedMirrorCalendar)).block();

        // Then the delegated mirror is filtered.
        assertThat(result)
            .describedAs("Resolver should filter a delegated mirror calendar when requester is not the delegate")
            .isEmpty();
    }

    @Test
    void resolveShouldKeepMultipleReadableCalendarsAndFilterUnreadableCalendars() {
        // Given several readable calendars and one not readable calendar.
        OpenPaaSUser delegatedSourceUser = sabreDavExtension.newTestUser();
        OpenPaaSUser unreadableUser = sabreDavExtension.newTestUser();

        CalendarURL ownCalendar = CalendarURL.from(requester.id());
        CalendarURL subscribedSourceCalendar = createCalendar(sourceUser, "subscribed-readable-" + UUID.randomUUID(), PublicRight.READ);
        davTestHelper.subscribeToSharedCalendar(requester, SubscribedCalendarRequest.builder()
            .id("subscribed-" + UUID.randomUUID())
            .sourceUserId(sourceUser.id().value())
            .sourceCalendarId(subscribedSourceCalendar.calendarId().value())
            .name("Subscribed readable source")
            .color("#123456")
            .readOnly(true)
            .build());

        CalendarURL delegatedSourceCalendar = createCalendar(delegatedSourceUser, "delegated-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);
        davTestHelper.grantDelegation(delegatedSourceUser, delegatedSourceCalendar, requester, "dav:read");

        CalendarURL notReadableCalendar = createCalendar(unreadableUser, "not-readable-" + UUID.randomUUID(), PublicRight.HIDE_ALL_EVENT);

        // When resolving all requested calendars.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester,
            List.of(ownCalendar, subscribedSourceCalendar, notReadableCalendar, delegatedSourceCalendar)).block();

        // Then all readable calendars are kept and the not readable calendar is filtered.
        assertThat(result)
            .describedAs("Resolver should keep all readable calendars and filter unreadable calendars")
            .containsOnly(
                entry(ownCalendar, ownCalendar),
                entry(subscribedSourceCalendar, subscribedSourceCalendar),
                entry(delegatedSourceCalendar, delegatedSourceCalendar));
    }

    @Test
    void resolveShouldKeepResourceCalendarWhenRequesterIsResourceAdministrator() {
        // Given requester is administrator of resource calendar R/R.
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .getDomain()
            .block();
        ResourceId resourceId = createResource(domain, requester, List.of(requester));
        CalendarURL resourceCalendar = CalendarURL.from(resourceId.asOpenPaaSId());
        calDavClient.grantReadWriteRights(domain.id(), resourceId, List.of(requester.username())).block();

        // When resolving R/R.
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(resourceCalendar)).block();

        // Then R/R is kept.
        assertThat(result)
            .describedAs("Resolver should keep a resource calendar readable by its administrator")
            .containsExactly(entry(resourceCalendar, resourceCalendar));
    }

    @Test
    void resolveShouldTranslateSubscribedResourceCalendarToResourceCalendar() {
        // Given requester subscribed to resource calendar R/R.
        OpenPaaSDomain domain = sabreDavExtension.dockerSabreDavSetup()
            .getOpenPaaSProvisioningService()
            .getDomain()
            .block();
        ResourceId resourceId = createResource(domain, sourceUser, List.of());
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
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(subscribedCalendar)).block();

        // Then R/R is returned.
        assertThat(result)
            .describedAs("Resolver should translate a subscribed resource calendar to its source resource calendar")
            .containsExactly(entry(subscribedCalendar, resourceCalendar));
    }

    @Test
    void resolveShouldFilterResourceCalendarFromAnotherDomain() {
        // Given resource calendar R/R belongs to another domain.
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
        Map<CalendarURL, CalendarURL> result = testee.resolve(requester, List.of(resourceCalendar)).block();

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

    private CalendarURL createSubscribedSourceCalendar() {
        CalendarURL sourceCalendar = createCalendar(sourceUser, "source-" + UUID.randomUUID(), PublicRight.READ);
        davTestHelper.subscribeToSharedCalendar(requester, SubscribedCalendarRequest.builder()
            .id("subscribed-" + UUID.randomUUID())
            .sourceUserId(sourceUser.id().value())
            .sourceCalendarId(sourceCalendar.calendarId().value())
            .name("Subscribed source")
            .color("#123456")
            .readOnly(true)
            .build());

        return sourceCalendar;
    }

    private CalendarURL createDelegatedSourceCalendar(OpenPaaSUser delegate, PublicRight sourcePublicRight) {
        CalendarURL sourceCalendar = createCalendar(sourceUser, "delegated-" + UUID.randomUUID(), sourcePublicRight);
        davTestHelper.grantDelegation(sourceUser, sourceCalendar, delegate, "dav:read");

        return sourceCalendar;
    }

    private CalendarURL findMirrorCalendar(OpenPaaSUser user) {
        return calDavClient.findUserCalendars(user.username(), user.id())
            .filter(calendarURL -> !calendarURL.equals(CalendarURL.from(user.id())))
            .next()
            .blockOptional()
            .orElseThrow(() -> new AssertionError("No mirror calendar found"));
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
