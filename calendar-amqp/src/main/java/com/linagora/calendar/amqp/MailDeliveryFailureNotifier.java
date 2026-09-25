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

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.amqp.model.CalendarEventInviteNotificationEmail;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventFields.Person;
import com.linagora.calendar.storage.event.EventParseUtils;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
public class MailDeliveryFailureNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(MailDeliveryFailureNotifier.class);
    private static final TemplateType TEMPLATE = new TemplateType("mail-delivery-failed");

    private final SettingsBasedResolver settingsResolver;
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final MailSender.Factory mailSenderFactory;
    private final MailAddress fromMailAddress;
    private final MimeAttachment logoAttachment;

    @Inject
    public MailDeliveryFailureNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                     MailTemplateConfiguration templateConfiguration,
                                     MessageGenerator.Factory messageGeneratorFactory,
                                     MailSender.Factory mailSenderFactory,
                                     @Named("calendar-logo") byte[] calendarLogo) {
        this.settingsResolver = settingsResolver;
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.mailSenderFactory = mailSenderFactory;
        this.fromMailAddress = templateConfiguration.sender().asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.logoAttachment = MimeAttachment.builder()
            .contentType(ContentType.of("image/png"))
            .cid(Cid.from("logo"))
            .inline()
            .content(calendarLogo)
            .fileName("logo.png")
            .build();
    }

    public Mono<Void> notify(CalendarEventInviteNotificationEmail event, String failedSubject) {
        MailAddress organizer = EventParseUtils.getOrganizer(event.base().getFirstVEvent())
            .map(Person::email)
            .orElse(event.base().senderEmail());
        Username organizerUser = Username.fromMailAddress(organizer);
        return settingsResolver.resolveOrDefault(organizerUser, Username.fromMailAddress(event.base().senderEmail()))
            .flatMap(settings -> generateMail(event, failedSubject, organizer, organizerUser, settings)
                .flatMap(mailSenderFactory::send))
            .doOnSuccess(ignored -> LOGGER.warn("Invitation to {} could not be delivered; notified organizer {}",
                event.base().recipientEmail(), organizer));
    }

    private Mono<Mail> generateMail(CalendarEventInviteNotificationEmail event, String failedSubject, MailAddress organizer,
                                    Username organizerUser, ResolvedSettings settings) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), TEMPLATE))
            .flatMap(generator -> generator.generate(organizerUser, fromMailAddress,
                toPugModel(event, failedSubject), List.of(logoAttachment)))
            .map(message -> new Mail(templateConfiguration.sender(), List.of(organizer), message))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Map<String, Object> toPugModel(CalendarEventInviteNotificationEmail event, String failedSubject) {
        return ImmutableMap.of("content", ImmutableMap.of(
            "failedSubject", StringUtils.defaultString(failedSubject),
            "recipient", event.base().recipientEmail().asString()));
    }
}
