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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mime4j.dom.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.api.DomainUrl;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportResult;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportResult.FailedItem;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportType;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;

import reactor.core.publisher.Mono;

class ImportMailReportRenderTest {
    private static final Username BOB = Username.of("bob@open-paas.org");

    private static class ModelCapturingMessageGenerator extends MessageGenerator {
        private final AtomicReference<Map<String, Object>> capturedModel = new AtomicReference<>();

        ModelCapturingMessageGenerator() {
            super(null, null, Optional.empty(), null);
        }

        @Override
        public Mono<Message> generate(Username recipient, MailAddress fromAddress, Map<String, Object> scopedVariable, List<MimeAttachment> mimeAttachments) {
            capturedModel.set(scopedVariable);
            return Mono.fromCallable(() -> Message.Builder.of().setBody("x", StandardCharsets.UTF_8).build());
        }
    }

    private ModelCapturingMessageGenerator generator;
    private ImportMailReportRender testee;

    @BeforeEach
    void setUp() throws Exception {
        generator = new ModelCapturingMessageGenerator();

        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .calendarSpaUrl(Optional.of(DomainUrl.of(URI.create("https://calendar.example.com").toURL())))
            .contactSpaUrl(Optional.of(DomainUrl.of(URI.create("https://contacts.example.com").toURL())))
            .adminPassword(Optional.of("admin"))
            .build();

        testee = new ImportMailReportRender(
            new MailTemplateConfiguration("file:///unused", MaybeSender.getMailSender("no-reply@open-paas.org")),
            (language, type) -> generator,
            configuration,
            new byte[0], new byte[0]);
    }

    @Test
    void reportModelShouldExposeFailedContactValuesToTheTemplates() {
        ImportResult result = ImportResult.failed(new FailedItem(Map.of("email", "john.doe@example.com")));

        testee.generateMail(ImportType.VCARD, Language.ENGLISH, result, BOB).block();

        assertThat(failedList())
            .containsExactly(Map.of("email", "john.doe@example.com"));
    }

    @Test
    void reportModelShouldExposeFailedEventValuesToTheTemplates() {
        Map<String, Object> eventValues = Map.of(
            "uid", "event-1",
            "summary", "Meeting",
            "start", "2025-06-20T10:00:00Z",
            "end", "2025-06-20T11:00:00Z");
        ImportResult result = ImportResult.failed(new FailedItem(eventValues));

        testee.generateMail(ImportType.ICS, Language.ENGLISH, result, BOB).block();

        assertThat(failedList())
            .containsExactly(eventValues);
    }

    @Test
    void reportModelShouldResolveTheBaseUrlAgainstTheDomainOfTheImportingUser() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .calendarSpaUrl(Optional.of(DomainUrl.parse("https://calendar.{domain}")))
            .adminPassword(Optional.of("admin"))
            .build();
        ImportMailReportRender perDomainTestee = new ImportMailReportRender(
            new MailTemplateConfiguration("file:///unused", "no-reply@{domain}"),
            (language, type) -> generator,
            configuration,
            new byte[0], new byte[0]);

        perDomainTestee.generateMail(ImportType.ICS, Language.ENGLISH, ImportResult.succeed(), BOB).block();

        assertThat(content().get("baseUrl"))
            .isEqualTo("https://calendar.open-paas.org");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> content() {
        return (Map<String, Object>) generator.capturedModel.get().get("content");
    }

    @SuppressWarnings("unchecked")
    private List<Object> failedList() {
        Map<String, Object> content = (Map<String, Object>) generator.capturedModel.get().get("content");
        return (List<Object>) content.get("jobFailedList");
    }
}
