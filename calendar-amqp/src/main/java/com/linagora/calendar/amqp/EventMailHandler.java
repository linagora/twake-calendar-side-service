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

import static com.linagora.calendar.smtp.template.MimeAttachment.ATTACHMENT_DISPOSITION_TYPE;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.amqp.model.CalendarEventReplyNotificationEmail;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedLocator;

import reactor.core.publisher.Mono;

public class EventMailHandler {

    enum EventType {
        INVITATION,
        REPLY,
        CANCELLATION,
        COUNTER;

        public TemplateType asTemplateType() {
            return new TemplateType("event-" + this.name().toLowerCase(Locale.US));
        }
    }

    private final MailSender.Factory mailSenderFactory;
    private final SettingsBasedLocator settingsBasedLocator;
    private final SimpleSessionProvider sessionProvider;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;

    @Inject
    @Singleton
    public EventMailHandler(MailSender.Factory mailSenderFactory,
                            SettingsBasedLocator settingsBasedLocator,
                            MessageGenerator.Factory messageGeneratorFactory,
                            EventInCalendarLinkFactory eventInCalendarLinkFactory,
                            SimpleSessionProvider sessionProvider) {
        this.mailSenderFactory = mailSenderFactory;
        this.settingsBasedLocator = settingsBasedLocator;
        this.sessionProvider = sessionProvider;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
    }

    interface EventMessageGenerator {
        Mono<Message> generate(Locale locale);
    }

    class ReplyEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventReplyNotificationEmail event;
        private final Username recipientUser;

        public ReplyEventMessageGenerator(CalendarEventReplyNotificationEmail event, Username recipientUser) {
            this.event = event;
            this.recipientUser = recipientUser;
        }

        @Override
        public Mono<Message> generate(Locale locale) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(locale), EventType.REPLY.asTemplateType()))
                .flatMap(messageGenerator -> generateReplyMessage(locale, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate reply event message", error)));
        }

        private Mono<Message> generateReplyMessage(Locale locale,
                                                   MessageGenerator messageGenerator) {

            Map<String, Object> model = event.toReplyContentModelBuilder()
                .locale(locale)
                .translator(messageGenerator.getI18nTranslator())
                .eventInCalendarLink(eventInCalendarLinkFactory)
                .buildAsMap();

            byte[] calendarAsBytes = event.base().event().toString().getBytes(StandardCharsets.UTF_8);

            List<MimeAttachment> attachments = List.of(MimeAttachment.builder()
                    .contentType(ContentType.of("text/calendar; charset=UTF-8; method=REPLY"))
                    .content(calendarAsBytes)
                    .build(),
                MimeAttachment.builder()
                    .contentType(ContentType.of("application/ics"))
                    .content(calendarAsBytes)
                    .dispositionType(ATTACHMENT_DISPOSITION_TYPE)
                    .fileName("meeting.ics")
                    .build());

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser, Optional.of(fromAddress), model, attachments);
        }
    }

    public Mono<Void> handReplyEvent(CalendarEventReplyNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return handleEvent(new ReplyEventMessageGenerator(event, recipientUser), recipientUser, event.base().senderEmail());
    }

    private Mono<Void> handleEvent(EventMessageGenerator eventMessageGenerator, Username recipientUser, MailAddress senderEmail) {
        return settingsBasedLocator.getLanguageUserSetting(
                sessionProvider.createSession(recipientUser),
                sessionProvider.createSession(Username.fromMailAddress(senderEmail)))
            .flatMap(eventMessageGenerator::generate)
            .flatMap(mailMessage -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(new Mail(MaybeSender.of(senderEmail),
                    ImmutableList.of(Throwing.supplier(recipientUser::asMailAddress).get()), mailMessage))));
    }

}
