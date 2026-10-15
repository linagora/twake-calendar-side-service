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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.mail.internet.InternetAddress;

import org.apache.commons.io.IOUtils;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Entity;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.dom.Multipart;
import org.apache.james.mime4j.dom.TextBody;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

class MessageGeneratorTest {
    private static final MimeAttachment LOGO = MimeAttachment.builder()
        .contentType(ContentType.of("image/png"))
        .cid(Cid.from("logo"))
        .content("logo".getBytes(StandardCharsets.UTF_8))
        .fileName("logo.png")
        .inline()
        .build();
    private static final MimeAttachment ICS = MimeAttachment.builder()
        .contentType(ContentType.of("text/calendar; charset=UTF-8"))
        .content("BEGIN:VCALENDAR".getBytes(StandardCharsets.UTF_8))
        .fileName("meeting.ics")
        .dispositionType(MimeAttachment.ATTACHMENT_DISPOSITION_TYPE)
        .build();
    private static final Map<String, Object> MODEL = ImmutableMap.of(
        "content", ImmutableMap.of(
            "baseUrl", "https://contacts.example.com",
            "jobSucceedCount", 9,
            "jobFailedCount", 0));

    private Path templateDirectory;
    private I18NTranslator translator;
    private HtmlBodyRenderer htmlBodyRenderer;

    @BeforeEach
    void setUp() throws Exception {
        templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "import-contacts");
        translator = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile())
            .forLocale(Locale.ENGLISH);
        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toString());
    }

    @Test
    void generateShouldWrapTextAndHtmlIntoMultipartAlternative() throws Exception {
        Message message = reparse(generate(textBodyRenderer(), List.of()));

        Multipart alternative = multipart(multipart(message).getBodyParts().getFirst());

        assertThat(alternative.getSubType()).isEqualTo("alternative");
        assertThat(alternative.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/plain", "text/html");
    }

    @Test
    void textAlternativeShouldBeRenderedFromTextTemplate() throws Exception {
        Message message = reparse(generate(textBodyRenderer(), List.of()));

        Entity textPart = multipart(multipart(message).getBodyParts().getFirst()).getBodyParts().getFirst();

        assertThat(textPart.getCharset()).isEqualTo("UTF-8");
        assertThat(textPart.getHeader().getField("Content-Language").getBody()).isEqualTo("en");
        assertThat(IOUtils.toString(((TextBody) textPart.getBody()).getReader()))
            .isEqualToNormalizingNewlines("""
                Import Report

                Your Import is done see below the report :
                - 9 Contact(s) imported successfully
                - 0 Contact(s) not imported
                """);
    }

    @Test
    void inlineAttachmentsShouldBeRelatedToTheHtmlAlternative() throws Exception {
        Message message = reparse(generate(textBodyRenderer(), List.of(LOGO, ICS)));

        Multipart mixed = multipart(message);
        Multipart alternative = multipart(mixed.getBodyParts().getFirst());
        Multipart related = multipart(alternative.getBodyParts().get(1));

        assertThat(mixed.getSubType()).isEqualTo("mixed");
        assertThat(mixed.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("multipart/alternative", "text/calendar");
        assertThat(alternative.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/plain", "multipart/related");
        assertThat(related.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/html", "image/png");
    }

    @Test
    void generateShouldSendHtmlOnlyWhenNoTextTemplate() throws Exception {
        Message message = reparse(generate(Optional.empty(), List.of(LOGO)));

        Multipart mixed = multipart(message);
        Multipart related = multipart(mixed.getBodyParts().getFirst());

        assertThat(mixed.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("multipart/related");
        assertThat(related.getBodyParts())
            .extracting(Entity::getMimeType)
            .containsExactly("text/html", "image/png");
    }

    private Optional<TextBodyRenderer> textBodyRenderer() throws IOException {
        return TextBodyRenderer.forPath(templateDirectory.toString());
    }

    private Message generate(Optional<TextBodyRenderer> textBodyRenderer, List<MimeAttachment> attachments) throws Exception {
        MessageGenerator testee = new MessageGenerator(translator, htmlBodyRenderer, textBodyRenderer, null);
        return testee.generate(new InternetAddress("bob@domain.tld"), new InternetAddress("no-reply@domain.tld"), MODEL, attachments)
            .block();
    }

    private Message reparse(Message message) throws IOException {
        byte[] bytes = DefaultMessageWriter.asBytes(message);
        return new DefaultMessageBuilder().parseMessage(new ByteArrayInputStream(bytes));
    }

    private Multipart multipart(Entity entity) {
        return (Multipart) entity.getBody();
    }
}
