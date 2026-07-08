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

import static reactor.core.scheduler.Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.mail.internet.InternetAddress;

import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.dom.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

@Singleton
public class BookingLinkCancellationAcknowledgementNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(BookingLinkCancellationAcknowledgementNotifier.class);
    private static final TemplateType EVENT_BOOKING_REQUEST_CANCELLED_TEMPLATE = new TemplateType("event-booking-request-cancelled");

    private final SettingsBasedResolver settingsResolver;
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final MailSender.Factory mailSenderFactory;
    private final MailAddress fromMailAddress;
    private final Scheduler mailScheduler;

    @Inject
    public BookingLinkCancellationAcknowledgementNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                                          MailTemplateConfiguration templateConfiguration,
                                                          MessageGenerator.Factory messageGeneratorFactory,
                                                          MailSender.Factory mailSenderFactory) {
        this.settingsResolver = settingsResolver;
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.mailSenderFactory = mailSenderFactory;
        this.fromMailAddress = templateConfiguration.sender().asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.mailScheduler = Schedulers.newBoundedElastic(1, DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "bookingLinkCancellationMailScheduler");
    }

    public Mono<Void> notify(BookedEventCancelled cancelled) {
        MailAddress recipient = cancelled.bookerEmail();

        LOGGER.debug("Preparing booking cancellation acknowledgement email to {}", recipient.asString());

        return settingsResolver.resolveOrDefault(cancelled.organizer().username())
            .flatMap(settings -> sendAcknowledgementMail(settings, cancelled, recipient));
    }

    private Mono<Void> sendAcknowledgementMail(ResolvedSettings settings, BookedEventCancelled cancelled, MailAddress recipient) {
        return generateMail(settings, cancelled, recipient)
            .flatMap(mail -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(mail)))
            .subscribeOn(mailScheduler);
    }

    private Mono<Mail> generateMail(ResolvedSettings settings, BookedEventCancelled cancelled, MailAddress recipient) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(
                new Language(settings.locale()), EVENT_BOOKING_REQUEST_CANCELLED_TEMPLATE))
            .flatMap(messageGenerator -> generateMessage(settings, cancelled, recipient, messageGenerator))
            .map(message -> new Mail(templateConfiguration.sender(), List.of(recipient), message));
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          BookedEventCancelled cancelled,
                                          MailAddress recipient,
                                          MessageGenerator messageGenerator) {
        return Mono.fromCallable(() -> new InternetAddress(recipient.asString()))
            .flatMap(recipientAddress -> Mono.fromCallable(() -> new InternetAddress(fromMailAddress.asString()))
                .flatMap(fromAddress -> messageGenerator.generate(recipientAddress, fromAddress,
                    PugModel.toPugModel(cancelled, settings.locale(), settings.zoneId()), List.of())));
    }

    interface PugModel {
        String CONTENT = "content";
        String START = "start";
        String END = "end";
        String OWNER = "owner";
        String OWNER_NAME = "cn";
        String OWNER_EMAIL = "email";

        static Map<String, Object> toPugModel(BookedEventCancelled cancelled, Locale locale, ZoneId zoneId) {
            ZonedDateTime start = ZonedDateTime.ofInstant(cancelled.start(), zoneId);
            ZonedDateTime end = ZonedDateTime.ofInstant(cancelled.end(), zoneId);

            PersonModel owner = PersonModel.from(cancelled.organizerPerson());

            Map<String, Object> content = ImmutableMap.of(
                START, new EventTimeModel(start).toPugModel(locale, zoneId),
                END, new EventTimeModel(end).toPugModel(locale, zoneId),
                OWNER, ImmutableMap.of(
                    OWNER_NAME, owner.displayName(),
                    OWNER_EMAIL, owner.email()));

            return ImmutableMap.of(CONTENT, content);
        }
    }
}
