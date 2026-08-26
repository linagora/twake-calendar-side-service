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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.stream.RawField;
import org.apache.james.util.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventInCalendarLinkFactory;
import com.linagora.calendar.smtp.template.content.model.ReplyContentModelBuilder;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Attendee;
import net.fortuna.ical4j.model.property.immutable.ImmutableMethod;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Sends the iTIP REPLY notification email to the organizer when an attendee answers an invitation through the
 * participation action links.
 */
@Singleton
public class EventParticipationReplyNotifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventParticipationReplyNotifier.class);
    private static final TemplateType EVENT_REPLY_TEMPLATE = new TemplateType("event-reply");

    private final SettingsBasedResolver settingsResolver;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventInCalendarLinkFactory eventInCalendarLinkFactory;
    private final MailSender.Factory mailSenderFactory;

    @Inject
    public EventParticipationReplyNotifier(@Named("language_timezone") SettingsBasedResolver settingsResolver,
                                           MessageGenerator.Factory messageGeneratorFactory,
                                           EventInCalendarLinkFactory eventInCalendarLinkFactory,
                                           MailSender.Factory mailSenderFactory) {
        this.settingsResolver = settingsResolver;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventInCalendarLinkFactory = eventInCalendarLinkFactory;
        this.mailSenderFactory = mailSenderFactory;
    }

    public Mono<Void> notifyOrganizer(Calendar updatedEvent, MailAddress attendee, MailAddress organizer) {
        Username organizerUser = Username.fromMailAddress(organizer);
        VEvent vEvent = EventParseUtils.getFirstEvent(updatedEvent);

        return Mono.justOrEmpty(findAttendee(vEvent, attendee))
            .switchIfEmpty(Mono.error(() -> new IllegalArgumentException(
                "Attendee " + attendee.asString() + " is not part of the event")))
            .flatMap(replyingAttendee -> settingsResolver.resolveOrDefault(organizerUser, Username.fromMailAddress(attendee))
                .flatMap(settings -> generateMessage(settings, updatedEvent, vEvent, replyingAttendee, organizerUser)))
            .flatMap(message -> {
                LOGGER.debug("Sending participation reply mail of {} to organizer {}", attendee.asString(), organizer.asString());
                return send(message, attendee, organizer);
            })
            .doOnSuccess(any -> AuditTrail.entry()
                .action("IMIP")
                .action(EventParticipationReplyNotifier.class.getName())
                .parameters(() -> ImmutableMap.of(
                    "sender", attendee.asString(),
                    "recipient", organizer.asString(),
                    "eventUid", EventParseUtils.extractEventUid(updatedEvent)))
                .log("IMIP reply mail sent"));
    }

    private Mono<Void> send(Message message, MailAddress attendee, MailAddress organizer) {
        return mailSenderFactory.create()
            .flatMap(mailSender -> mailSender.send(new Mail(MaybeSender.of(attendee), ImmutableList.of(organizer), message)));
    }

    private Mono<Message> generateMessage(ResolvedSettings settings,
                                          Calendar updatedEvent,
                                          VEvent vEvent,
                                          EventFields.Person replyingAttendee,
                                          Username organizerUser) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(settings.locale()), EVENT_REPLY_TEMPLATE))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(messageGenerator -> {
                MailAddress fromAddress = replyingAttendee.email();
                return messageGenerator.resolveInternetAddress(Username.fromMailAddress(fromAddress))
                    .flatMap(fromInternetAddress -> {
                        Map<String, Object> model = ReplyContentModelBuilder.from(vEvent, replyingAttendee)
                            .locale(settings.locale())
                            .timeZoneDisplay(settings.zoneId())
                            .translator(messageGenerator.getI18nTranslator())
                            .eventInCalendarLink(eventInCalendarLinkFactory)
                            .senderDisplayName(fromInternetAddress.getPersonal())
                            .buildAsMap();

                        return messageGenerator.generate(organizerUser, fromInternetAddress, model,
                            replyAttachments(updatedEvent, vEvent, replyingAttendee));
                    });
            })
            .map(message -> {
                message.getHeader().addField(new RawField("Auto-Submitted", "auto-generated"));
                return message;
            });
    }

    /**
     * An iTIP REPLY carries the sole attendee it originates from.
     */
    private List<MimeAttachment> replyAttachments(Calendar updatedEvent, VEvent vEvent, EventFields.Person replyingAttendee) {
        VEvent replyEvent = vEvent.copy();
        replyEvent.getAttendees().stream()
            .filter(attendee -> !matches(attendee, replyingAttendee.email()))
            .toList()
            .forEach(replyEvent::remove);

        byte[] replyAsBytes = CalendarUtil.withMethod(CalendarUtil.withSingleVEvent(updatedEvent, replyEvent), ImmutableMethod.REPLY)
            .toString()
            .getBytes(StandardCharsets.UTF_8);

        return List.of(
            MimeAttachment.builder()
                .contentType(ContentType.of("text/calendar; charset=UTF-8; method=" + ImmutableMethod.REPLY.getValue()))
                .content(replyAsBytes)
                .build(),
            MimeAttachment.builder()
                .contentType(ContentType.of("application/ics"))
                .content(replyAsBytes)
                .dispositionType(ATTACHMENT_DISPOSITION_TYPE)
                .fileName("meeting.ics")
                .build());
    }

    private Optional<EventFields.Person> findAttendee(VEvent vEvent, MailAddress attendee) {
        return EventParseUtils.getAttendees(vEvent).stream()
            .filter(person -> Strings.CI.equals(person.email().asString(), attendee.asString()))
            .findFirst();
    }

    private boolean matches(Attendee attendee, MailAddress mailAddress) {
        return Strings.CI.equals(attendee.getCalAddress().toASCIIString(), "mailto:" + mailAddress.asString());
    }
}
