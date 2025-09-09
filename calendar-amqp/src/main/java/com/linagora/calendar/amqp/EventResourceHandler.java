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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.LocationModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.ResourceId;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class EventResourceHandler {

    public static final String RESOURCE_REPLY_URI = "/calendar/api/resources/{resourceId}/{eventId}/participation?status={partStat}&referrer=email";

    public static final Function<EventFields.Person, PersonModel> PERSON_TO_MODEL =
        person -> new PersonModel(person.cn(), person.email().asString());

    public static final Function<Calendar, VEvent> GET_FIRST_VEVENT_FUNCTION =
        calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));

    public static final TemplateType TEMPLATE_TYPE = new TemplateType("resource-request");

    private static final Logger LOGGER = LoggerFactory.getLogger(EventResourceHandler.class);

    private final ResourceDAO resourceDAO;
    private final MailSender.Factory mailSenderFactory;
    private final SimpleSessionProvider sessionProvider;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final OpenPaaSUserDAO userDAO;
    private final SettingsBasedResolver settingsBasedResolver;
    private final EventEmailFilter eventEmailFilter;
    private final MaybeSender maybeSender;
    private final MailAddress senderAddress;
    private final String resourceReplyURL;

    public EventResourceHandler(ResourceDAO resourceDAO,
                                MailSender.Factory mailSenderFactory,
                                SimpleSessionProvider sessionProvider,
                                MessageGenerator.Factory messageGeneratorFactory,
                                OpenPaaSUserDAO userDAO,
                                SettingsBasedResolver settingsBasedResolver, EventEmailFilter eventEmailFilter,
                                MailTemplateConfiguration mailTemplateConfiguration,
                                @Named("spaCalendarUrl") String spaCalendarUrl) {
        this.resourceDAO = resourceDAO;
        this.mailSenderFactory = mailSenderFactory;
        this.sessionProvider = sessionProvider;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.userDAO = userDAO;
        this.settingsBasedResolver = settingsBasedResolver;
        this.eventEmailFilter = eventEmailFilter;
        this.maybeSender = mailTemplateConfiguration.sender();
        this.senderAddress = maybeSender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.resourceReplyURL = spaCalendarUrl + RESOURCE_REPLY_URI;
    }

    public Mono<Void> handleCreateEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle create event with resource message containing resourceId {} and resourceEventId {} and eventPath {}",
            message.resourceId(), message.eventId(), message.eventPath());
        return resourceDAO.findById(new ResourceId(message.resourceId()))
            .flatMap(resource -> Flux.fromIterable(resource.administrators())
                .flatMap(resourceAdministrator -> userDAO.retrieve(resourceAdministrator.refId()))
                .filter(Throwing.predicate(openPaaSUser -> eventEmailFilter.shouldProcess(openPaaSUser.username().asMailAddress())))
                .flatMap(openPaaSUser -> sendMail(message, resource.name(), openPaaSUser.username()))
                .then());
    }

    public Mono<Void> handleAcceptEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle accept event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return Mono.empty();
    }

    public Mono<Void> handleDeclineEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle decline event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return Mono.empty();
    }

    private Mono<Void> sendMail(CalendarResourceMessageDTO calendarResourceMessageDTO, String resourceName, Username recipientUser) {
        return getUserSettings(recipientUser)
            .flatMap(resolvedSettings -> {
                Locale locale = resolvedSettings.locale();
                Map<String, Object> model = toPugModel(calendarResourceMessageDTO, resourceName, locale, resolvedSettings.zoneId());
                return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(locale), TEMPLATE_TYPE))
                    .flatMap(messageGenerator -> messageGenerator.generate(recipientUser, senderAddress, model, List.of()))
                    .flatMap(message -> mailSenderFactory.create()
                        .flatMap(Throwing.function(mailSender -> mailSender.send(new Mail(maybeSender, List.of(recipientUser.asMailAddress()), message)))));
            });
    }

    private Mono<SettingsBasedResolver.ResolvedSettings> getUserSettings(Username user) {
        return settingsBasedResolver.readSavedSettings(sessionProvider.createSession(user))
            .defaultIfEmpty(SettingsBasedResolver.ResolvedSettings.DEFAULT)
            .doOnError(error -> {
                if (!(error instanceof DomainNotFoundException)) {
                    LOGGER.error("Error resolving user settings for {}, will use default settings: {}",
                        user.asString(), SettingsBasedResolver.ResolvedSettings.DEFAULT, error);
                }
            }).onErrorResume(error -> Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));
    }

    private Map<String, Object> toPugModel(CalendarResourceMessageDTO calendarResourceMessageDTO,
                                           String resourceName,
                                           Locale locale,
                                           ZoneId zoneToDisplay) {
        VEvent vEvent = GET_FIRST_VEVENT_FUNCTION.apply(calendarResourceMessageDTO.ics());

        String basicLink = resourceReplyURL.replace("{resourceId}", calendarResourceMessageDTO.resourceId())
            .replace("{eventId}", calendarResourceMessageDTO.eventId());
        String acceptLink = basicLink.replace("{partStat}", PartStat.ACCEPTED.getValue());
        String declineLink = basicLink.replace("{partStat}", PartStat.DECLINED.getValue());

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", toPugModel(vEvent, locale, zoneToDisplay))
            .put("resourceName", resourceName)
            .put("acceptLink", acceptLink)
            .put("declineLink", declineLink);

        return ImmutableMap.of("content", contentBuilder.build(),
            "subject.resourceName", resourceName);
    }

    private Map<String, Object> toPugModel(VEvent vEvent, Locale locale, ZoneId zoneToDisplay) {
        PersonModel organizer = PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        ZonedDateTime startDate = EventParseUtils.getStartTime(vEvent);
        List<EventFields.Person> resourceList = EventParseUtils.getResources(vEvent);

        ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();
        eventBuilder.put("organizer", organizer.toPugModel())
            .put("attendees", EventParseUtils.getAttendees(vEvent).stream()
                .collect(ImmutableMap.toImmutableMap(attendee -> attendee.email().asString(),
                    attendee -> PERSON_TO_MODEL.apply(attendee).toPugModel())))
            .put("summary", summary)
            .put("allDay", EventParseUtils.isAllDay(vEvent))
            .put("start", new EventTimeModel(startDate).toPugModel(locale, zoneToDisplay))
            .put("end", EventParseUtils.getEndTime(vEvent).map(endDate -> new EventTimeModel(endDate).toPugModel(locale, zoneToDisplay))
                .orElseThrow(() -> new IllegalArgumentException("Missing endDate")))
            .put("hasResources", !resourceList.isEmpty())
            .put("resources", resourceList.stream()
                .collect(ImmutableMap.toImmutableMap(resource -> resource.email().asString(),
                    resource -> PERSON_TO_MODEL.apply(resource).toPugModel())));
        EventParseUtils.getLocation(vEvent).ifPresent(location -> eventBuilder.put("location", new LocationModel(location).toPugModel()));
        EventParseUtils.getDescription(vEvent).ifPresent(description -> eventBuilder.put("description", description));
        EventParseUtils.getPropertyValueIgnoreCase(vEvent, "X-OPENPAAS-VIDEOCONFERENCE")
            .ifPresent(value -> eventBuilder.put("videoConferenceLink", value));

        return eventBuilder.build();
    }
}
