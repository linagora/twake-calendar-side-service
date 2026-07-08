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

import static com.linagora.calendar.smtp.template.MimeAttachment.ATTACHMENT_DISPOSITION_TYPE;

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

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventFields;

import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Singleton
public class PublicAgendaCancellationNotifier {
    private static final TemplateType EVENT_CANCEL_TEMPLATE = new TemplateType("event-cancel");
    private static final String CALENDAR_CONTENT_TYPE_PREFIX = "text/calendar; charset=UTF-8; method=";
    private static final String ICS_CONTENT_TYPE = "application/ics";
    private static final String ICS_FILENAME = "meeting.ics";

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
            .flatMap(settings -> Mono.fromCallable(() -> cancelled.organizer().username().asMailAddress())
                .flatMap(organizerMailAddress -> generateMail(settings, cancelled, organizerMailAddress)
                    .flatMap(mail -> mailSenderFactory.create().flatMap(mailSender -> mailSender.send(mail)))))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private Mono<Mail> generateMail(ResolvedSettings settings, BookedEventCancelled cancelled, MailAddress organizerMailAddress) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), EVENT_CANCEL_TEMPLATE))
            .flatMap(messageGenerator -> generateMessage(settings, cancelled, messageGenerator))
            .map(Throwing.function(message -> new Mail(templateConfiguration.sender(), List.of(organizerMailAddress), message)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          BookedEventCancelled cancelled,
                                          MessageGenerator messageGenerator) {
        List<MimeAttachment> attachments = List.of(
            MimeAttachment.builder()
                .contentType(ContentType.of(CALENDAR_CONTENT_TYPE_PREFIX + ImmutableMethod.CANCEL.getValue()))
                .content(cancelled.cancelIcsBytes())
                .build(),
            MimeAttachment.builder()
                .contentType(ContentType.of(ICS_CONTENT_TYPE))
                .content(cancelled.cancelIcsBytes())
                .dispositionType(ATTACHMENT_DISPOSITION_TYPE)
                .fileName(ICS_FILENAME)
                .build());

        return messageGenerator.generate(cancelled.organizer().username(),
            fromMailAddress,
            PugModel.toPugModel(cancelled, settings.locale(), settings.zoneId(), eventInCalendarLinkFactory),
            attachments);
    }

    interface PugModel {
        String CONTENT = "content";
        String EVENT = "event";
        String ORGANIZER = "organizer";
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
        String SUBJECT_ORGANIZER = "subject.organizer";

        static Map<String, Object> toPugModel(BookedEventCancelled cancelled,
                                              Locale locale,
                                              ZoneId zoneId,
                                              EventInCalendarLinkFactory eventInCalendarLinkFactory) {
            ZonedDateTime start = ZonedDateTime.ofInstant(cancelled.start(), zoneId);
            ZonedDateTime end = ZonedDateTime.ofInstant(cancelled.end(), zoneId);

            PersonModel organizer = PersonModel.from(cancelled.organizerPerson());

            ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
            eventBuilder.put(ORGANIZER, ImmutableMap.of("cn", organizer.displayName(), "email", organizer.email()))
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
                SUBJECT_SUMMARY, cancelled.summary(),
                SUBJECT_ORGANIZER, organizer.displayName());
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
