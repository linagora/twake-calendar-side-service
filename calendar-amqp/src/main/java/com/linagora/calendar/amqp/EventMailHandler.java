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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.user.api.UsersRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.amqp.model.CalendarEventCancelNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventCounterNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventInviteNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventReplyNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventUpdateNotificationEmail;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.LanguageSettingReader;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.TimeZoneSettingReader;

import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;

public class EventMailHandler {

    enum EventType {
        INVITE,
        UPDATE,
        REPLY,
        CANCEL,
        COUNTER;

        public TemplateType asTemplateType() {
            return new TemplateType("event-" + this.name().toLowerCase(Locale.US));
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(EventMailHandler.class);
    private final MailSender.Factory mailSenderFactory;
    private final SimpleSessionProvider sessionProvider;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;
    private final UsersRepository usersRepository;
    private final SettingsBasedResolver settingsBasedResolver;

    @Inject
    @Singleton
    public EventMailHandler(MailSender.Factory mailSenderFactory,
                            ConfigurationResolver configurationResolver,
                            MessageGenerator.Factory messageGeneratorFactory,
                            EventInCalendarLinkFactory eventInCalendarLinkFactory,
                            SimpleSessionProvider sessionProvider,
                            UsersRepository usersRepository) {
        this(mailSenderFactory, messageGeneratorFactory, eventInCalendarLinkFactory, sessionProvider, usersRepository,
            SettingsBasedResolver.of(configurationResolver,
                Set.of(LanguageSettingReader.INSTANCE, TimeZoneSettingReader.INSTANCE)));
    }

    public EventMailHandler(MailSender.Factory mailSenderFactory,
                            MessageGenerator.Factory messageGeneratorFactory,
                            EventInCalendarLinkFactory eventInCalendarLinkFactory,
                            SimpleSessionProvider sessionProvider,
                            UsersRepository usersRepository,
                            SettingsBasedResolver settingsBasedResolver) {
        this.mailSenderFactory = mailSenderFactory;
        this.sessionProvider = sessionProvider;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
        this.usersRepository = usersRepository;
        this.settingsBasedResolver = settingsBasedResolver;
    }

    interface EventMessageGenerator {
        Mono<Message> generate(ResolvedSettings resolvedSettings);

        static List<MimeAttachment> createAttachments(byte[] calendarAsBytes, Method method) {
            return List.of(
                MimeAttachment.builder()
                    .contentType(ContentType.of("text/calendar; charset=UTF-8; method=" + method.getValue()))
                    .content(calendarAsBytes)
                    .build(),
                MimeAttachment.builder()
                    .contentType(ContentType.of("application/ics"))
                    .content(calendarAsBytes)
                    .dispositionType(ATTACHMENT_DISPOSITION_TYPE)
                    .fileName("meeting.ics")
                    .build()
            );
        }
    }

    class InviteEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventInviteNotificationEmail event;
        private final Username recipientUser;
        private final boolean isInternalUser;

        public InviteEventMessageGenerator(CalendarEventInviteNotificationEmail event, Username recipientUser, boolean isInternalUser) {
            this.event = event;
            this.recipientUser = recipientUser;
            this.isInternalUser = isInternalUser;
        }

        @Override
        public Mono<Message> generate(ResolvedSettings resolvedSettings) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), EventType.INVITE.asTemplateType()))
                .flatMap(messageGenerator -> generateInvitationMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate invitation event message", error)));
        }

        private Mono<Message> generateInvitationMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.REQUEST);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser,
                Optional.of(fromAddress),
                event.toPugModel(resolvedSettings.locale(), resolvedSettings.zoneId(), eventInCalendarLinkFactory, isInternalUser), attachments);
        }
    }

    class UpdateEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventUpdateNotificationEmail event;
        private final Username recipientUser;
        private final boolean isInternalUser;

        public UpdateEventMessageGenerator(CalendarEventUpdateNotificationEmail event, Username recipientUser, boolean isInternalUser) {
            this.event = event;
            this.recipientUser = recipientUser;
            this.isInternalUser = isInternalUser;
        }

        @Override
        public Mono<Message> generate(ResolvedSettings resolvedSettings) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), EventType.UPDATE.asTemplateType()))
                .flatMap(messageGenerator -> generateUpdateMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate update event message", error)));
        }

        private Mono<Message> generateUpdateMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.REQUEST);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser,
                Optional.of(fromAddress),
                event.toPugModel(resolvedSettings.locale(), resolvedSettings.zoneId(), eventInCalendarLinkFactory, isInternalUser), attachments);
        }
    }

    class CancelEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventCancelNotificationEmail event;
        private final Username recipientUser;
        private final boolean isInternalUser;

        public CancelEventMessageGenerator(CalendarEventCancelNotificationEmail event, Username recipientUser, boolean isInternalUser) {
            this.event = event;
            this.recipientUser = recipientUser;
            this.isInternalUser = isInternalUser;
        }

        @Override
        public Mono<Message> generate(ResolvedSettings resolvedSettings) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), EventType.CANCEL.asTemplateType()))
                .flatMap(messageGenerator -> generateCancelMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate cancel event message", error)));
        }

        private Mono<Message> generateCancelMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.CANCEL);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser, Optional.of(fromAddress),
                event.toPugModel(resolvedSettings.locale(), resolvedSettings.zoneId(), eventInCalendarLinkFactory, isInternalUser), attachments);
        }
    }

    class ReplyEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventReplyNotificationEmail event;
        private final Username recipientUser;

        public ReplyEventMessageGenerator(CalendarEventReplyNotificationEmail event, Username recipientUser) {
            this.event = event;
            this.recipientUser = recipientUser;
        }

        @Override
        public Mono<Message> generate(ResolvedSettings resolvedSettings) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), EventType.REPLY.asTemplateType()))
                .flatMap(messageGenerator -> generateReplyMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate reply event message", error)));
        }

        private Mono<Message> generateReplyMessage(ResolvedSettings resolvedSettings,
                                                   MessageGenerator messageGenerator) {

            Map<String, Object> model = event.toReplyContentModelBuilder()
                .locale(resolvedSettings.locale())
                .timeZoneDisplay(resolvedSettings.zoneId())
                .translator(messageGenerator.getI18nTranslator())
                .eventInCalendarLink(eventInCalendarLinkFactory)
                .buildAsMap();

            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.REPLY);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser, Optional.of(fromAddress), model, attachments);
        }
    }

    class CounterEventMessageGenerator implements EventMessageGenerator {
        private final CalendarEventCounterNotificationEmail event;
        private final Username recipientUser;

        public CounterEventMessageGenerator(CalendarEventCounterNotificationEmail event, Username recipientUser) {
            this.event = event;
            this.recipientUser = recipientUser;
        }

        @Override
        public Mono<Message> generate(ResolvedSettings resolvedSettings) {
            return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), EventType.COUNTER.asTemplateType()))
                .flatMap(messageGenerator -> generateCounterMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate counter event message", error)));
        }

        private Mono<Message> generateCounterMessage(ResolvedSettings resolvedSettings,
                                                     MessageGenerator messageGenerator) {
            Map<String, Object> model = event.toCounterContentModelBuilder()
                .locale(resolvedSettings.locale())
                .zoneToDisplay(resolvedSettings.zoneId())
                .translator(messageGenerator.getI18nTranslator())
                .eventInCalendarLink(eventInCalendarLinkFactory)
                .buildAsMap();

            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.COUNTER);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser, Optional.of(fromAddress), model, attachments);
        }
    }

    public Mono<Void> handInviteEvent(CalendarEventInviteNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return Mono.from(usersRepository.containsReactive(recipientUser))
            .flatMap(isInternalUser -> handleEvent(new InviteEventMessageGenerator(event, recipientUser, isInternalUser), recipientUser, event.base().senderEmail()));
    }

    public Mono<Void> handleUpdateEvent(CalendarEventUpdateNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return Mono.from(usersRepository.containsReactive(recipientUser))
            .flatMap(isInternalUser -> handleEvent(new UpdateEventMessageGenerator(event, recipientUser, isInternalUser), recipientUser, event.base().senderEmail()));
    }

    public Mono<Void> handleCancelEvent(CalendarEventCancelNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return Mono.from(usersRepository.containsReactive(recipientUser))
            .flatMap(isInternalUser -> handleEvent(new CancelEventMessageGenerator(event, recipientUser, isInternalUser), recipientUser, event.base().senderEmail()));
    }

    public Mono<Void> handReplyEvent(CalendarEventReplyNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return handleEvent(new ReplyEventMessageGenerator(event, recipientUser), recipientUser, event.base().senderEmail());
    }

    public Mono<Void> handCounterEvent(CalendarEventCounterNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return handleEvent(new CounterEventMessageGenerator(event, recipientUser), recipientUser, event.base().senderEmail());
    }

    private Mono<Void> handleEvent(EventMessageGenerator eventMessageGenerator, Username recipientUser, MailAddress senderEmail) {
        Mono<ResolvedSettings> resolvedSettingsPublisher = getUserSettings(recipientUser)
            .switchIfEmpty(getUserSettings(Username.fromMailAddress(senderEmail)))
            .defaultIfEmpty(ResolvedSettings.DEFAULT)
            .onErrorResume(error -> Mono.just(ResolvedSettings.DEFAULT));

        return resolvedSettingsPublisher
            .flatMap(eventMessageGenerator::generate)
            .flatMap(mailMessage -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(new Mail(MaybeSender.of(senderEmail),
                    ImmutableList.of(Throwing.supplier(recipientUser::asMailAddress).get()), mailMessage))));
    }

    private Mono<ResolvedSettings> getUserSettings(Username user) {
        return settingsBasedResolver.readSavedSettings(sessionProvider.createSession(user))
            .doOnError(error -> LOGGER.error("Error resolving user settings for {}, will use default settings: {}",
                user.asString(), ResolvedSettings.DEFAULT, error));
    }

}
