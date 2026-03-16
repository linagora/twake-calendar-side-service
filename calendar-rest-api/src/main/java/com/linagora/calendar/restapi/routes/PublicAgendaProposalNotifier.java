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
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.api.EventParticipationActionLinkFactory;
import com.linagora.calendar.api.EventParticipationActionLinkFactory.ActionLinks;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingCreated;
import com.linagora.calendar.restapi.routes.BookingLinkReservationService.BookingRequest.BookingAttendee;
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

import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class PublicAgendaProposalNotifier {
    private static final TemplateType EVENT_PROPOSE_TEMPLATE = new TemplateType("event-propose");
    private static final String CALENDAR_CONTENT_TYPE_PREFIX = "text/calendar; charset=UTF-8; method=";
    private static final String ICS_CONTENT_TYPE = "application/ics";
    private static final String ICS_FILENAME = "meeting.ics";

    private final SettingsBasedResolver settingsResolver;
    private final EventParticipationActionLinkFactory actionLinkFactory;
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;
    private final MailSender.Factory mailSenderFactory;
    private final MailAddress fromMailAddress;

    @Inject
    @Singleton
    public PublicAgendaProposalNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                        EventParticipationActionLinkFactory actionLinkFactory,
                                        MailTemplateConfiguration templateConfiguration,
                                        MessageGenerator.Factory messageGeneratorFactory,
                                        EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                        MailSender.Factory mailSenderFactory) {
        this.settingsResolver = settingsResolver;
        this.actionLinkFactory = actionLinkFactory;
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
        this.mailSenderFactory = mailSenderFactory;
        this.fromMailAddress = templateConfiguration.sender().asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
    }

    public Mono<Void> notify(BookingCreated bookingCreated) {
        return settingsResolver.resolveOrDefault(bookingCreated.organizer().username())
            .flatMap(settings -> Mono.fromCallable(() -> bookingCreated.organizer().username().asMailAddress())
                .flatMap(organizerMailAddress -> actionLinkFactory.generateLinks(organizerMailAddress,
                        organizerMailAddress,
                        bookingCreated.eventIcsResult().eventIdAsString(),
                        bookingCreated.bookingLink().calendarUrl().base().value())
                    .flatMap(actionLinks -> generateMail(settings, bookingCreated, actionLinks))
                    .flatMap(mail -> mailSenderFactory.create().flatMap(mailSender -> mailSender.send(mail)))))
            .subscribeOn(Schedulers.boundedElastic())
            .then();
    }

    private Mono<Mail> generateMail(ResolvedSettings settings, BookingCreated bookingCreated, ActionLinks actionLinks) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), EVENT_PROPOSE_TEMPLATE))
            .flatMap(messageGenerator -> generateMessage(settings, bookingCreated, actionLinks, messageGenerator))
            .map(Throwing.function(message -> new Mail(templateConfiguration.sender(), List.of(bookingCreated.organizer().username().asMailAddress()), message)))
            .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          BookingCreated bookingCreated,
                                          ActionLinks actionLinks,
                                          MessageGenerator messageGenerator) {
        List<MimeAttachment> attachments = List.of(
            MimeAttachment.builder()
                .contentType(ContentType.of(CALENDAR_CONTENT_TYPE_PREFIX + ImmutableMethod.REQUEST.getValue()))
                .content(bookingCreated.eventIcsResult().icsBytes())
                .build(),
            MimeAttachment.builder()
                .contentType(ContentType.of(ICS_CONTENT_TYPE))
                .content(bookingCreated.eventIcsResult().icsBytes())
                .dispositionType(ATTACHMENT_DISPOSITION_TYPE)
                .fileName(ICS_FILENAME)
                .build());

        return messageGenerator.generate(bookingCreated.organizer().username(),
            fromMailAddress,
            PugModel.toPugModel(bookingCreated, actionLinks, settings.locale(), settings.zoneId(), eventInCalendarLinkFactory),
            attachments);
    }

    interface PugModel {
        String CONTENT = "content";
        String EVENT = "event";
        String PROPOSER = "proposer";
        String ORGANIZER = "organizer";
        String ATTENDEES = "attendees";
        String SUMMARY = "summary";
        String ALL_DAY = "allDay";
        String START = "start";
        String END = "end";
        String HAS_RESOURCES = "hasResources";
        String RESOURCES = "resources";
        String DESCRIPTION = "description";
        String VIDEO_CONFERENCE_LINK = "videoConferenceLink";
        String SEE_IN_CALENDAR_LINK = "seeInCalendarLink";
        String YES_LINK = "yesLink";
        String MAYBE_LINK = "maybeLink";
        String NO_LINK = "noLink";
        String SUBJECT_SUMMARY = "subject.summary";
        String SUBJECT_PROPOSER = "subject.proposer";

        static Map<String, Object> toPugModel(BookingCreated bookingCreated,
                                              ActionLinks actionLinks,
                                              Locale locale,
                                              ZoneId zoneId,
                                              EventInCalendarLinkFactory eventInCalendarLinkFactory) {
            ZonedDateTime start = ZonedDateTime.ofInstant(bookingCreated.request().slotStartUtc(), zoneId);
            ZonedDateTime end = start.plus(bookingCreated.bookingLink().duration());

            ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
            eventBuilder.put(PROPOSER, displayName(bookingCreated.request().creator()))
                .put(ORGANIZER, new PersonModel(bookingCreated.organizer().fullName(), bookingCreated.organizer().username().asString()).toPugModel())
                .put(ATTENDEES, attendeesAsPugModel(bookingCreated))
                .put(SUMMARY, bookingCreated.request().title())
                .put(ALL_DAY, false)
                .put(START, new EventTimeModel(start).toPugModel(locale, zoneId))
                .put(END, new EventTimeModel(end).toPugModel(locale, zoneId))
                .put(HAS_RESOURCES, false)
                .put(RESOURCES, Map.of());

            Optional.ofNullable(bookingCreated.request().notes())
                .filter(StringUtils::isNotBlank)
                .ifPresent(notes -> eventBuilder.put(DESCRIPTION, notes));

            bookingCreated.eventIcsResult().visioLink()
                .ifPresent(link -> eventBuilder.put(VIDEO_CONFERENCE_LINK, link.toString()));

            return ImmutableMap.of(
                CONTENT, ImmutableMap.builder()
                    .put(EVENT, eventBuilder.build())
                    .put(SEE_IN_CALENDAR_LINK, eventInCalendarLinkFactory.getEventInCalendarLink(start))
                    .put(YES_LINK, actionLinks.yes())
                    .put(MAYBE_LINK, actionLinks.maybe())
                    .put(NO_LINK, actionLinks.no())
                    .build(),
                SUBJECT_SUMMARY, bookingCreated.request().title(),
                SUBJECT_PROPOSER, displayName(bookingCreated.request().creator()));
        }

        private static Map<String, Object> attendeesAsPugModel(BookingCreated bookingCreated) {
            return Stream.concat(Stream.of(bookingCreated.request().creator()), bookingCreated.request().additionalAttendees().stream())
                .collect(Collectors.toMap(attendee -> attendee.email().asString(), PugModel::attendeeToPugModel));
        }

        private static Map<String, Object> attendeeToPugModel(BookingAttendee attendee) {
            return new PersonModel(displayName(attendee), attendee.email().asString()).toPugModel();
        }

        private static String displayName(BookingAttendee attendee) {
            return StringUtils.defaultIfBlank(attendee.name(), attendee.email().asString());
        }
    }
}
