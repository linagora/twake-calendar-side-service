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

package com.linagora.calendar.webadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import org.apache.james.core.Domain;
import org.apache.james.task.Task;
import org.apache.james.task.TaskId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.dto.AddressBookReportXmlResponse;
import com.linagora.calendar.saas.contact.CommonContactEventConverter;
import com.linagora.calendar.saas.contact.CommonContactMessage;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactPublisher;
import com.linagora.calendar.saas.contact.CommonContactSyncEvent;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask.RunningOptions;
import com.linagora.calendar.webadmin.task.CommonContactRepublishTask.Scope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class CommonContactRepublishServiceTest {
    private static final OpenPaaSDomain DOMAIN = new OpenPaaSDomain(Domain.of("org.example.com"), new OpenPaaSId("domain-id"));
    private static final AddressBookURL BOOK = new AddressBookURL(DOMAIN.id(), "domain-members");
    private static final String EMPTY_REPORT = "<d:multistatus xmlns:d=\"DAV:\"/>";
    private static final String CONTACT_RESPONSE = """
        <d:response>
          <d:href>{bookPath}/{uid}.vcf</d:href>
          <d:propstat>
            <d:prop><card:address-data>BEGIN:VCARD
        VERSION:4.0
        UID:{uid}
        FN:Test contact
        END:VCARD
        </card:address-data></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status>
          </d:propstat>
        </d:response>
        """;

    private CardDavClient client;
    private CommonContactPublisher publisher;
    private CommonContactRepublishService service;
    private CommonContactRepublishService.Context context;
    private List<CommonContactMessage> messages;
    private TaskId taskId;

    @BeforeEach
    void setUp() {
        client = mock(CardDavClient.class);
        publisher = mock(CommonContactPublisher.class);
        OpenPaaSUserDAO userDAO = mock(OpenPaaSUserDAO.class);
        OpenPaaSDomainDAO domainDAO = mock(OpenPaaSDomainDAO.class);
        service = new CommonContactRepublishService(userDAO, domainDAO, client,
            new CommonContactEventConverter(userDAO, domainDAO), publisher);
        context = new CommonContactRepublishService.Context();
        messages = new CopyOnWriteArrayList<>();
        taskId = TaskId.generateTaskId();
        when(client.listDomainAddressBookUrls(any(), any())).thenReturn(Flux.just(BOOK));
        when(publisher.publish(any())).thenAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return Mono.empty();
        });
    }

    @Test
    void shouldCompleteAnEmptyAddressBookWithZeroContacts() {
        when(client.reportDomainAddressBookContacts(DOMAIN.id(), BOOK)).thenReturn(report(EMPTY_REPORT));

        assertThat(republish()).isEqualTo(Task.Result.COMPLETED);
        assertThat(messages).containsExactly(
            CommonContactSyncEvent.start(new CommonContactOutboundEvent.Audience.Domain(DOMAIN.domain()),
                URI.create("addressbooks/domain-id/domain-members"), taskId.asString() + ":addressbooks/domain-id/domain-members"),
            CommonContactSyncEvent.end(new CommonContactOutboundEvent.Audience.Domain(DOMAIN.domain()),
                URI.create("addressbooks/domain-id/domain-members"), taskId.asString() + ":addressbooks/domain-id/domain-members", true, 0));
    }

    @Test
    void shouldCloseTheSyncAsIncompleteWhenReadingFails() {
        when(client.reportDomainAddressBookContacts(DOMAIN.id(), BOOK)).thenReturn(Mono.error(new IllegalStateException("DAV unavailable")));

        assertThat(republish()).isEqualTo(Task.Result.PARTIAL);
        assertIncompleteEnd();
        assertThat(context.snapshot().failedAddressBookCount()).isEqualTo(1);
    }

    @Test
    void shouldStopFailedAddressBookAndContinueWithTheNextOne() {
        AddressBookURL nextBook = new AddressBookURL(DOMAIN.id(), "next-book");
        when(client.listDomainAddressBookUrls(any(), any())).thenReturn(Flux.just(BOOK, nextBook));
        when(client.reportDomainAddressBookContacts(DOMAIN.id(), BOOK)).thenReturn(reportContacts(BOOK, "contact-a", "contact-b", "contact-c"));
        when(client.reportDomainAddressBookContacts(DOMAIN.id(), nextBook)).thenReturn(reportContacts(nextBook, "contact-d"));
        doAnswer(invocation -> {
            CommonContactMessage message = invocation.getArgument(0);
            messages.add(message);
            if (message instanceof CommonContactOutboundEvent contact && contact.uid().value().equals("contact-b")) {
                // A slow failure must not allow C to start while B is still in flight.
                return Mono.delay(Duration.ofMillis(100)).then(Mono.error(new IllegalStateException("Publish B failed")));
            }
            return Mono.empty();
        }).when(publisher).publish(any());

        assertThat(republish()).isEqualTo(Task.Result.PARTIAL);

        assertThat(messages.stream()
            .filter(CommonContactOutboundEvent.class::isInstance)
            .map(CommonContactOutboundEvent.class::cast)
            .map(event -> event.uid().value())
            .toList()).containsExactly("contact-a", "contact-b", "contact-d");
        assertThat(messages).hasSize(7);
        Audience audience = new Audience.Domain(DOMAIN.domain());
        assertThat(messages.get(3)).isEqualTo(CommonContactSyncEvent.end(audience,
            URI.create("addressbooks/domain-id/domain-members"), taskId.asString() + ":addressbooks/domain-id/domain-members", false, 1));
        assertThat(messages.get(4)).isEqualTo(CommonContactSyncEvent.start(audience,
            URI.create("addressbooks/domain-id/next-book"), taskId.asString() + ":addressbooks/domain-id/next-book"));
        assertThat(messages.getLast()).isEqualTo(CommonContactSyncEvent.end(audience,
            URI.create("addressbooks/domain-id/next-book"), taskId.asString() + ":addressbooks/domain-id/next-book", true, 1));
        assertThat(context.snapshot().processedContactCount()).isEqualTo(2);
        assertThat(context.snapshot().failedContactCount()).isEqualTo(1);
        assertThat(context.snapshot().failedAddressBookCount()).isEqualTo(1);
    }

    private Task.Result republish() {
        return service.republish(taskId, context, RunningOptions.of(100),
            new Scope.ForDomain(DOMAIN, Scope.Selection.DOMAIN_ADDRESS_BOOKS)).block();
    }

    private void assertIncompleteEnd() {
        assertThat(messages.getLast()).isInstanceOfSatisfying(CommonContactSyncEvent.class, event -> {
            assertThat(event.action()).isEqualTo(CommonContactSyncEvent.Action.SYNC_END);
            assertThat(event.complete()).isFalse();
            assertThat(event.count()).isZero();
        });
    }

    private Mono<AddressBookReportXmlResponse> reportContacts(AddressBookURL book, String... uids) {
        String contacts = Arrays.stream(uids)
            .map(uid -> CONTACT_RESPONSE.replace("{bookPath}", book.asUri().toASCIIString()).replace("{uid}", uid))
            .collect(Collectors.joining());
        return report("""
            <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
            {contacts}
            </d:multistatus>
            """.replace("{contacts}", contacts));
    }

    private Mono<AddressBookReportXmlResponse> report(String xml) {
        return Mono.just(new AddressBookReportXmlResponse(xml.getBytes(StandardCharsets.UTF_8)));
    }
}
