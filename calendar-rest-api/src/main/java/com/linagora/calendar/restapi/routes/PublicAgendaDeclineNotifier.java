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

import static com.linagora.calendar.storage.event.EventParseUtils.DuplicateAttendeePolicy.KEEP_FIRST;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.dom.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.SubjectRenderer;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.LocationModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Negative counterpart of the booking confirmed email: when the owner of a public agenda declines a booking,
 * the booker is told the event will not happen.
 */
public class PublicAgendaDeclineNotifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublicAgendaDeclineNotifier.class);
    private static final TemplateType EVENT_BOOKING_DECLINED_TEMPLATE = new TemplateType("event-booking-declined");

    private final SettingsBasedResolver settingsResolver;
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final MailSender.Factory mailSenderFactory;
    private final MailAddress fromMailAddress;

    @Inject
    public PublicAgendaDeclineNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                       MailTemplateConfiguration templateConfiguration,
                                       MessageGenerator.Factory messageGeneratorFactory,
                                       MailSender.Factory mailSenderFactory) {
        this.settingsResolver = settingsResolver;
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.mailSenderFactory = mailSenderFactory;
        this.fromMailAddress = templateConfiguration.sender().asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
    }

    public Mono<Void> notify(BookedEventDeclined declined) {
        return settingsResolver.resolveOrDefault(declined.owner())
            .flatMap(settings -> sendDeclineMail(settings, declined))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> sendDeclineMail(ResolvedSettings settings, BookedEventDeclined declined) {
        MailAddress recipient = declined.booker().email();
        LOGGER.debug("Preparing public agenda decline email to {} for event '{}'",
            recipient.asString(), EventParseUtils.getSummary(declined.event()).orElse(StringUtils.EMPTY));

        return generateMail(settings, declined, recipient)
            .flatMap(mail -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(mail)));
    }

    private Mono<Mail> generateMail(ResolvedSettings settings, BookedEventDeclined declined, MailAddress recipient) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), EVENT_BOOKING_DECLINED_TEMPLATE))
            .flatMap(messageGenerator -> generateMessage(settings, declined, recipient, messageGenerator))
            .map(message -> new Mail(templateConfiguration.sender(), List.of(recipient), message));
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          BookedEventDeclined declined,
                                          MailAddress recipient,
                                          MessageGenerator messageGenerator) {
        return Mono.fromCallable(() -> PugModel.toPugModel(declined, settings.locale(), settings.zoneId(),
                messageGenerator.getI18nTranslator()))
            .flatMap(model -> Mono.fromCallable(() -> new InternetAddress(recipient.asString()))
                .flatMap(recipientAddress -> Mono.fromCallable(() -> new InternetAddress(fromMailAddress.asString()))
                    .flatMap(fromAddress -> messageGenerator.generate(recipientAddress, fromAddress, model, List.of()))));
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
        String LOCATION = "location";
        String DESCRIPTION = "description";
        String VIDEO_CONFERENCE_LINK = "videoConferenceLink";
        String BOOKING_DECLINED_MESSAGE = "bookingDeclinedMessage";
        String PROPOSER_EMAIL = "proposerEmail";
        String SUBJECT_SUMMARY = "subject.summary";
        String SUBJECT_ORGANIZER = "subject.organizer";
        String SUBJECT_PROPOSAL_OWNER = "subject.proposal_owner";
        String BOOKING_DECLINED_MESSAGE_KEY = "booking_declined_message";
        String PROPOSAL_OWNER_YOU_KEY = "proposal_owner_you";
        String X_OPENPAAS_VIDEOCONFERENCE = "X-OPENPAAS-VIDEOCONFERENCE";

        static Map<String, Object> toPugModel(BookedEventDeclined declined,
                                              Locale locale,
                                              ZoneId zoneId,
                                              I18NTranslator translator) throws Exception {
            VEvent vEvent = declined.event();
            String proposalOwner = translator.get(PROPOSAL_OWNER_YOU_KEY);

            return ImmutableMap.of(
                CONTENT, ImmutableMap.of(
                    EVENT, eventAsPugModel(vEvent, locale, zoneId),
                    BOOKING_DECLINED_MESSAGE, SubjectRenderer.of(translator.get(BOOKING_DECLINED_MESSAGE_KEY))
                        .render(Map.of(SUBJECT_PROPOSAL_OWNER, proposalOwner)),
                    PROPOSER_EMAIL, declined.booker().email().asString()),
                SUBJECT_SUMMARY, EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY),
                SUBJECT_ORGANIZER, PersonModel.from(declined.organizer()).displayName(),
                SUBJECT_PROPOSAL_OWNER, proposalOwner);
        }

        private static Map<String, Object> eventAsPugModel(VEvent vEvent, Locale locale, ZoneId zoneId) {
            ZonedDateTime start = EventParseUtils.getStartTime(vEvent);

            ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
            eventBuilder.put(ORGANIZER, PersonModel.from(EventParseUtils.getOrganizer(vEvent)).toPugModel())
                .put(ATTENDEES, attendeesAsPugModel(vEvent))
                .put(SUMMARY, EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY))
                .put(ALL_DAY, EventParseUtils.isAllDay(vEvent))
                .put(START, new EventTimeModel(start).toPugModel(locale, zoneId));

            EventParseUtils.getEndTime(vEvent)
                .ifPresent(end -> eventBuilder.put(END, new EventTimeModel(end).toPugModel(locale, zoneId)));
            EventParseUtils.getLocation(vEvent)
                .ifPresent(location -> eventBuilder.put(LOCATION, new LocationModel(location).toPugModel()));
            EventParseUtils.getDescription(vEvent)
                .ifPresent(description -> eventBuilder.put(DESCRIPTION, description));
            EventParseUtils.getPropertyValueIgnoreCase(vEvent, X_OPENPAAS_VIDEOCONFERENCE)
                .ifPresent(link -> eventBuilder.put(VIDEO_CONFERENCE_LINK, link));

            return eventBuilder.build();
        }

        private static Map<String, Map<String, Object>> attendeesAsPugModel(VEvent vEvent) {
            return EventParseUtils.getAttendees(vEvent, KEEP_FIRST).stream()
                .collect(ImmutableMap.toImmutableMap(person -> person.email().asString(),
                    person -> PersonModel.from(person).toPugModel()));
        }
    }
}
