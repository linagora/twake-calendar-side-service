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

import static com.linagora.calendar.amqp.EventFieldConverter.extractCalendarURL;
import static com.linagora.calendar.smtp.template.MimeAttachment.ATTACHMENT_DISPOSITION_TYPE;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Domain;
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
import com.linagora.calendar.amqp.model.CalendarEventNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventReplyNotificationEmail;
import com.linagora.calendar.amqp.model.CalendarEventUpdateNotificationEmail;
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.ReplyContentModelBuilder;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.model.ResourceId;

import net.fortuna.ical4j.model.property.Method;
import net.fortuna.ical4j.model.property.Uid;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;
    private final UsersRepository usersRepository;
    private final ResourceDAO resourceDAO;
    private final OpenPaaSDomainDAO openPaaSDomainDAO;
    private final SettingsBasedResolver settingsResolver;
    private final EventParticipationActionLinkFactory participationActionLinkFactory;

    @Inject
    public EventMailHandler(MailSender.Factory mailSenderFactory,
                            MessageGenerator.Factory messageGeneratorFactory,
                            EventInCalendarLinkFactory eventInCalendarLinkFactory,
                            UsersRepository usersRepository, ResourceDAO resourceDAO,
                            OpenPaaSDomainDAO openPaaSDomainDAO,
                            @Named("language_timezone") SettingsBasedResolver settingsResolver,
                            EventParticipationActionLinkFactory participationActionLinkFactory) {
        this.mailSenderFactory = mailSenderFactory;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
        this.usersRepository = usersRepository;
        this.resourceDAO = resourceDAO;
        this.openPaaSDomainDAO = openPaaSDomainDAO;
        this.settingsResolver = settingsResolver;
        this.participationActionLinkFactory = participationActionLinkFactory;
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

        static Mono<ActionLinks> generateActionLinks(EventParticipationActionLinkFactory participationActionLinkFactory, CalendarEventNotificationEmail event) {
            return Mono.just(event.getFirstVEvent())
                .flatMap(vEvent -> {
                    MailAddress organizerMail = EventParseUtils.getOrganizer(vEvent).email();
                    MailAddress attendeeMail = event.recipientEmail();
                    String eventUid = vEvent.getUid().map(Uid::getValue).orElseThrow();
                    OpenPaaSId attendeeCalendarBaseId = extractCalendarURL(event.eventPath()).base();
                    return participationActionLinkFactory.generateLinks(organizerMail, attendeeMail, eventUid, attendeeCalendarBaseId.value());
                });
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
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(messageGenerator -> generateInvitationMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate invitation event message", error)));
        }

        private Mono<Message> generateInvitationMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.REQUEST);
            MailAddress fromAddress = event.base().senderEmail();

            return EventMessageGenerator.generateActionLinks(participationActionLinkFactory, event.base())
                .map(actionLinks -> event.toPugModel(resolvedSettings.locale(), resolvedSettings.zoneId(), eventInCalendarLinkFactory, isInternalUser, actionLinks))
                .flatMap(scopedVariable -> messageGenerator.generate(recipientUser, fromAddress, scopedVariable, attachments));
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
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(messageGenerator -> generateUpdateMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate update event message", error)));
        }

        private Mono<Message> generateUpdateMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.REQUEST);

            MailAddress fromAddress = event.base().senderEmail();

            return EventMessageGenerator.generateActionLinks(participationActionLinkFactory, event.base())
                .map(actionLinks -> event.toPugModel(resolvedSettings.locale(), resolvedSettings.zoneId(), eventInCalendarLinkFactory, isInternalUser, actionLinks))
                .flatMap(scopedVariable -> messageGenerator.generate(recipientUser, fromAddress, scopedVariable, attachments));
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
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(messageGenerator -> generateCancelMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate cancel event message", error)));
        }

        private Mono<Message> generateCancelMessage(ResolvedSettings resolvedSettings, MessageGenerator messageGenerator) {
            byte[] calendarAsBytes = event.base().eventAsBytes();
            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(calendarAsBytes, ImmutableMethod.CANCEL);

            MailAddress fromAddress = event.base().senderEmail();
            return messageGenerator.generate(recipientUser, fromAddress,
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
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(messageGenerator -> generateReplyMessage(resolvedSettings, messageGenerator))
                .onErrorResume(error -> Mono.error(new EventMailHandlerException("Error occurred when generate reply event message", error)));
        }

        private Mono<Message> generateReplyMessage(ResolvedSettings resolvedSettings,
                                                   MessageGenerator messageGenerator) {

            ReplyContentModelBuilder.SenderDisplayNameStep modelBuilder = event.toReplyContentModelBuilder()
                .locale(resolvedSettings.locale())
                .timeZoneDisplay(resolvedSettings.zoneId())
                .translator(messageGenerator.getI18nTranslator())
                .eventInCalendarLink(eventInCalendarLinkFactory);

            List<MimeAttachment> attachments = EventMessageGenerator.createAttachments(event.base().eventAsBytes(), ImmutableMethod.REPLY);

            MailAddress fromAddress = event.base().senderEmail();

            return messageGenerator.resolveInternetAddress(Username.fromMailAddress(fromAddress))
                .flatMap(fromInternetAddress -> {
                    Map<String, Object> model = modelBuilder.senderDisplayName(fromInternetAddress.getPersonal()).buildAsMap();
                    return messageGenerator.generate(recipientUser, fromInternetAddress, model, attachments);
                });
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
                .subscribeOn(Schedulers.boundedElastic())
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
            return messageGenerator.generate(recipientUser, fromAddress, model, attachments);
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

    public Mono<Void> handleReplyEvent(CalendarEventReplyNotificationEmail event) {
        MailAddress senderEmail = event.base().senderEmail();
        return isResourceEmail(senderEmail)
            .flatMap(isResource -> {
                if (isResource) {
                    LOGGER.debug("Ignoring reply event from resource email: {}", senderEmail.asString());
                    return Mono.empty();
                }
                MailAddress recipientEmail = event.base().recipientEmail();
                Username recipientUser = Username.fromMailAddress(recipientEmail);
                return handleEvent(new ReplyEventMessageGenerator(event, recipientUser), recipientUser, senderEmail);
            });
    }

    private Mono<Boolean> isResourceEmail(MailAddress senderEmail) {
        Domain domain = senderEmail.getDomain();
        String localPart = senderEmail.getLocalPart();

        return openPaaSDomainDAO.retrieve(domain)
            .flatMap(openPaaSDomain -> Mono.defer(() -> resourceDAO.exist(new ResourceId(localPart), openPaaSDomain.id())))
            .defaultIfEmpty(false);
    }

    public Mono<Void> handleCounterEvent(CalendarEventCounterNotificationEmail event) {
        MailAddress recipientEmail = event.base().recipientEmail();
        Username recipientUser = Username.fromMailAddress(recipientEmail);
        return handleEvent(new CounterEventMessageGenerator(event, recipientUser), recipientUser, event.base().senderEmail());
    }

    private Mono<Void> handleEvent(EventMessageGenerator eventMessageGenerator, Username recipientUser, MailAddress senderEmail) {
        return settingsResolver.resolveOrDefault(recipientUser, Username.fromMailAddress(senderEmail))
            .flatMap(eventMessageGenerator::generate)
            .flatMap(mailMessage -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(new Mail(MaybeSender.of(senderEmail),
                    ImmutableList.of(Throwing.supplier(recipientUser::asMailAddress).get()), mailMessage))));
    }
}
