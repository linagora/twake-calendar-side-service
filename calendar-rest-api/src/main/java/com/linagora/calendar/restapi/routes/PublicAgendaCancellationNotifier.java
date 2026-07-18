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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import jakarta.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.dom.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventFields;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
public class PublicAgendaCancellationNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAgendaCancellationNotifier.class);
    private static final TemplateType EVENT_BOOKING_CANCELLED_TEMPLATE = new TemplateType("event-booking-cancelled");

    private final SettingsBasedResolver settingsResolver;
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;
    private final MailSender.Factory mailSenderFactory;
    private final MailAddress fromMailAddress;

    @Inject
    public PublicAgendaCancellationNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                            MailTemplateConfiguration templateConfiguration,
                                            MessageGenerator.Factory messageGeneratorFactory,
                                            EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                            MailSender.Factory mailSenderFactory) {
        this.settingsResolver = settingsResolver;
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
        this.mailSenderFactory = mailSenderFactory;
        this.fromMailAddress = templateConfiguration.sender().asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
    }

    public Mono<Void> notify(BookedEventCancelled cancelled) {
        return settingsResolver.resolveOrDefault(cancelled.organizer().username())
            .flatMap(settings -> sendCancellationMails(settings, cancelled));
    }

    private Mono<Void> sendCancellationMails(ResolvedSettings settings, BookedEventCancelled cancelled) {
        return Mono.fromCallable(() -> ImmutableSet.<MailAddress>builder()
                .add(cancelled.organizer().username().asMailAddress())
                .add(cancelled.cancelledBy().email())
                .build())
            .flatMapMany(Flux::fromIterable)
            .flatMap(recipient -> sendCancellationMail(settings, cancelled, recipient))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private Mono<Void> sendCancellationMail(ResolvedSettings settings, BookedEventCancelled cancelled, MailAddress recipient) {
        LOGGER.debug("Preparing public agenda cancellation email to {} for event '{}'",
            recipient.asString(), cancelled.summary());
        return generateMail(settings, cancelled, recipient)
            .flatMap(mail -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(mail)));
    }

    private Mono<Mail> generateMail(ResolvedSettings settings, BookedEventCancelled cancelled, MailAddress recipient) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), EVENT_BOOKING_CANCELLED_TEMPLATE))
            .flatMap(messageGenerator -> generateMessage(settings, cancelled, recipient, messageGenerator))
            .map(message -> new Mail(templateConfiguration.sender(), List.of(recipient), message));
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          BookedEventCancelled cancelled,
                                          MailAddress recipient,
                                          MessageGenerator messageGenerator) {
        return Mono.fromCallable(() -> new InternetAddress(recipient.asString()))
            .flatMap(recipientAddress -> Mono.fromCallable(() -> new InternetAddress(fromMailAddress.asString()))
                .flatMap(fromAddress -> messageGenerator.generate(recipientAddress,
                    fromAddress,
                    PugModel.toPugModel(cancelled, recipient, settings.locale(), settings.zoneId(), eventInCalendarLinkFactory),
                    List.of())));
    }

    interface PugModel {
        String CONTENT = "content";
        String EVENT = "event";
        String ORGANIZER = "organizer";
        String CANCELLED_BY = "cancelledBy";
        String RECIPIENT_IS_BOOKER = "recipientIsBooker";
        String ATTENDEES = "attendees";
        String SUMMARY = "summary";
        String ALL_DAY = "allDay";
        String START = "start";
        String END = "end";
        String HAS_RESOURCES = "hasResources";
        String RESOURCES = "resources";
        String DESCRIPTION = "description";
        String SEE_IN_CALENDAR_LINK = "seeInCalendarLink";
        String SUBJECT_SUMMARY = "subject.summary";

        static Map<String, Object> toPugModel(BookedEventCancelled cancelled,
                                              MailAddress recipient,
                                              Locale locale,
                                              ZoneId zoneId,
                                              EventInCalendarLinkFactory eventInCalendarLinkFactory) {
            ZonedDateTime start = ZonedDateTime.ofInstant(cancelled.start(), zoneId);
            ZonedDateTime end = ZonedDateTime.ofInstant(cancelled.end(), zoneId);

            PersonModel organizer = PersonModel.from(cancelled.organizerPerson());
            PersonModel cancelledBy = PersonModel.from(cancelled.cancelledBy());

            ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
            eventBuilder.put(ORGANIZER, ImmutableMap.of("cn", organizer.displayName(), "email", organizer.email()))
                .put(CANCELLED_BY, ImmutableMap.of("cn", cancelledBy.displayName(), "email", cancelledBy.email()))
                .put(RECIPIENT_IS_BOOKER, Strings.CI.equals(cancelled.cancelledBy().email().asString(), recipient.asString()))
                .put(ATTENDEES, attendeesAsPugModel(cancelled))
                .put(SUMMARY, cancelled.summary())
                .put(ALL_DAY, false)
                .put(START, new EventTimeModel(start).toPugModel(locale, zoneId))
                .put(END, new EventTimeModel(end).toPugModel(locale, zoneId))
                .put(HAS_RESOURCES, false)
                .put(RESOURCES, Map.of());

            cancelled.description()
                .filter(StringUtils::isNotBlank)
                .ifPresent(description -> eventBuilder.put(DESCRIPTION, description));

            return ImmutableMap.of(
                CONTENT, ImmutableMap.of(
                    EVENT, eventBuilder.build(),
                    SEE_IN_CALENDAR_LINK, eventInCalendarLinkFactory.getEventInCalendarLink(start)),
                SUBJECT_SUMMARY, cancelled.summary());
        }

        private static Map<String, Object> attendeesAsPugModel(BookedEventCancelled cancelled) {
            return cancelled.attendees().stream()
                .collect(Collectors.toMap(attendee -> attendee.email().asString(), PugModel::attendeeToPugModel,
                    (left, right) -> left));
        }

        private static Map<String, Object> attendeeToPugModel(EventFields.Person attendee) {
            String displayName = Optional.ofNullable(StringUtils.trimToNull(attendee.cn())).orElseGet(() -> attendee.email().asString());
            return ImmutableMap.of("cn", displayName, "email", attendee.email().asString());
        }
    }
}
