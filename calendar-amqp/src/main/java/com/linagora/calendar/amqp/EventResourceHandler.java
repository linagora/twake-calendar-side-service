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

import java.net.URL;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.api.EventEmailFilter;
import com.linagora.calendar.api.JwtSigner;
import com.linagora.calendar.dav.CalDavEventRepository;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.i18n.I18NTranslator;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.EventTimeModel;
import com.linagora.calendar.smtp.template.content.model.LocationModel;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ResourceDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.model.Resource;
import com.linagora.calendar.storage.model.ResourceId;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class EventResourceHandler {

    private static final String PLACEHOLDER_RESOURCE_ID = "{resourceId}";
    private static final String PLACEHOLDER_EVENT_ID = "{eventId}";
    private static final String PLACEHOLDER_PART_STAT = "{partStat}";
    private static final String PLACEHOLDER_JWT = "{jwt}";

    public static final String RESOURCE_REPLY_URI = "/calendar/api/resources/" + PLACEHOLDER_RESOURCE_ID +
        "/" + PLACEHOLDER_EVENT_ID +
        "/participation?status=" + PLACEHOLDER_PART_STAT +
        "&referrer=email&jwt=" + PLACEHOLDER_JWT;

    public static final Function<EventFields.Person, PersonModel> PERSON_TO_MODEL =
        person -> new PersonModel(person.cn(), person.email().asString());

    public static final Function<Calendar, VEvent> GET_FIRST_VEVENT_FUNCTION =
        calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));

    public static final TemplateType RESOURCE_REQUEST_TEMPLATE_TYPE = new TemplateType("resource-request");
    public static final TemplateType RESOURCE_REPLY_TEMPLATE_TYPE = new TemplateType("resource-reply");
    public static final boolean APPROVED = true;

    private static final Logger LOGGER = LoggerFactory.getLogger(EventResourceHandler.class);

    private final ResourceDAO resourceDAO;
    private final MailSender.Factory mailSenderFactory;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final OpenPaaSUserDAO userDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final SettingsBasedResolver settingsResolver;
    private final EventEmailFilter eventEmailFilter;
    private final MaybeSender maybeSender;
    private final MailAddress senderAddress;
    private final String resourceReplyURL;
    private final JwtSigner jwtSigner;
    private final CalDavEventRepository calDavEventRepository;

    @Inject
    public EventResourceHandler(ResourceDAO resourceDAO,
                                MailSender.Factory mailSenderFactory,
                                MessageGenerator.Factory messageGeneratorFactory,
                                OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO,
                                @Named("language_timezone") SettingsBasedResolver settingsResolver,
                                EventEmailFilter eventEmailFilter,
                                MailTemplateConfiguration mailTemplateConfiguration,
                                @Named("selfUrl") URL calendarBaseUrl,
                                JwtSigner jwtSigner, CalDavEventRepository calDavEventRepository) {
        this.resourceDAO = resourceDAO;
        this.mailSenderFactory = mailSenderFactory;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
        this.settingsResolver = settingsResolver;
        this.eventEmailFilter = eventEmailFilter;
        this.maybeSender = mailTemplateConfiguration.sender();
        this.calDavEventRepository = calDavEventRepository;
        this.senderAddress = maybeSender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.resourceReplyURL = calendarBaseUrl.toString() + RESOURCE_REPLY_URI;
        this.jwtSigner = jwtSigner;
    }

    public Mono<Void> handleCreateEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle create event with resource message containing resourceId {} and resourceEventId {} and eventPath {}",
            message.resourceId(), message.eventId(), message.eventPath());
        return resourceDAO.findById(new ResourceId(message.resourceId()))
            .filter(resource -> !resource.deleted())
            .flatMap(resource -> {
                if (resource.administrators().isEmpty()) {
                    return acceptInvite(message, resource);
                } else {
                    return sendValidationEmailToAdministrators(message, resource);
                }
            });
    }

    private Mono<Void> acceptInvite(CalendarResourceMessageDTO message, Resource resource) {
        return domainDAO.retrieve(resource.domain())
            .flatMap(domain -> calDavEventRepository.updatePartStat(domain, resource.id(), message.eventId(), PartStat.ACCEPTED));
    }

    private Mono<Void> sendValidationEmailToAdministrators(CalendarResourceMessageDTO message, Resource resource) {
        return Flux.fromIterable(resource.administrators())
            .flatMap(resourceAdministrator -> userDAO.retrieve(resourceAdministrator.refId()), ReactorUtils.DEFAULT_CONCURRENCY)
            .filter(Throwing.predicate(openPaaSUser -> eventEmailFilter.shouldProcess(openPaaSUser.username().asMailAddress())))
            .flatMap(Throwing.function(openPaaSUser -> sendRequestMail(message, resource.name(), openPaaSUser.username().asMailAddress())))
            .then();
    }

    public Mono<Void> handleAcceptEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle accept event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return handleReplyEvent(message, APPROVED);
    }

    public Mono<Void> handleDeclineEvent(CalendarResourceMessageDTO message) {
        LOGGER.debug("Handle decline event with resource message containing resourceId {} and eventPath {}", message.resourceId(), message.eventPath());
        return handleReplyEvent(message, !APPROVED);
    }

    private Mono<Void> handleReplyEvent(CalendarResourceMessageDTO message, boolean approved) {
        return resourceDAO.findById(new ResourceId(message.resourceId()))
            .filter(resource -> !resource.deleted())
            .filter(resource -> !resource.administrators().isEmpty())
            .flatMap(resource -> Mono.just(getOrganizerEmail(message))
                .filter(eventEmailFilter::shouldProcess)
                .flatMap(organizerEmail -> sendReplyMail(message, resource.name(), organizerEmail, approved)));
    }

    private MailAddress getOrganizerEmail(CalendarResourceMessageDTO calendarResourceMessageDTO) {
        VEvent vEvent = GET_FIRST_VEVENT_FUNCTION.apply(calendarResourceMessageDTO.ics());
        return EventParseUtils.getOrganizer(vEvent).email();
    }

    private Mono<Void> sendRequestMail(CalendarResourceMessageDTO calendarResourceMessageDTO, String resourceName, MailAddress recipient) {
        return sendMail((SettingsBasedResolver.ResolvedSettings settings, I18NTranslator translator) ->
                toPugModel(calendarResourceMessageDTO, resourceName, settings.locale(), settings.zoneId()),
            recipient,
            RESOURCE_REQUEST_TEMPLATE_TYPE);
    }

    private Mono<Void> sendReplyMail(CalendarResourceMessageDTO calendarResourceMessageDTO, String resourceName, MailAddress recipient, boolean approved) {
        return sendMail((SettingsBasedResolver.ResolvedSettings settings, I18NTranslator translator) ->
                Mono.just(toPugModel(calendarResourceMessageDTO, resourceName, settings.locale(), settings.zoneId(), approved, translator)),
            recipient,
            RESOURCE_REPLY_TEMPLATE_TYPE);
    }

    private Mono<Void> sendMail(BiFunction<SettingsBasedResolver.ResolvedSettings, I18NTranslator, Mono<Map<String, Object>>> modelGenerator,
                                MailAddress recipient,
                                TemplateType templateType) {
        Username recipientUser = Username.fromMailAddress(recipient);
        return getUserSettings(recipientUser)
            .flatMap(resolvedSettings ->
                Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), templateType))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(messageGenerator ->
                        modelGenerator.apply(resolvedSettings, messageGenerator.getI18nTranslator())
                            .flatMap(model -> sendMessage(recipientUser, recipient, messageGenerator, model))));
    }

    private Mono<Void> sendMessage(Username recipientUser,
                                   MailAddress recipient,
                                   MessageGenerator messageGenerator,
                                   Map<String, Object> model) {
        return messageGenerator.generate(recipientUser, senderAddress, model, List.of())
            .flatMap(message -> mailSenderFactory.create()
                .flatMap(Throwing.function(mailSender ->
                    mailSender.send(new Mail(maybeSender, List.of(recipient), message)))));
    }

    private Mono<SettingsBasedResolver.ResolvedSettings> getUserSettings(Username user) {
        return settingsResolver.resolveOrDefault(user)
            .defaultIfEmpty(SettingsBasedResolver.ResolvedSettings.DEFAULT)
            .doOnError(error -> {
                if (!(error instanceof DomainNotFoundException)) {
                    LOGGER.error("Error resolving user settings for {}, will use default settings: {}",
                        user.asString(), SettingsBasedResolver.ResolvedSettings.DEFAULT, error);
                }
            }).onErrorResume(error -> Mono.just(SettingsBasedResolver.ResolvedSettings.DEFAULT));
    }

    private Mono<Map<String, Object>> toPugModel(CalendarResourceMessageDTO calendarResourceMessageDTO,
                                                        String resourceName,
                                                        Locale locale,
                                                        ZoneId zoneToDisplay) {
        String resourceId = calendarResourceMessageDTO.resourceId();
        String eventId = calendarResourceMessageDTO.eventId();

        return signAsJwt(resourceId, eventId)
            .map(jwt -> {
                String baseLink = resourceReplyURL
                    .replace(PLACEHOLDER_RESOURCE_ID, resourceId)
                    .replace(PLACEHOLDER_EVENT_ID, eventId)
                    .replace(PLACEHOLDER_JWT, jwt);

                String acceptLink = baseLink.replace("{partStat}", PartStat.ACCEPTED.getValue());
                String declineLink = baseLink.replace("{partStat}", PartStat.DECLINED.getValue());

                VEvent vEvent = GET_FIRST_VEVENT_FUNCTION.apply(calendarResourceMessageDTO.ics());

                ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
                contentBuilder.put("event", toPugModel(vEvent, locale, zoneToDisplay))
                    .put("resourceName", resourceName)
                    .put("acceptLink", acceptLink)
                    .put("declineLink", declineLink);

                return ImmutableMap.of(
                    "content", contentBuilder.build(),
                    "subject.resourceName", resourceName
                );
            });
    }

    private Mono<String> signAsJwt(String resourceId, String eventId) {
        return jwtSigner.generate(ImmutableMap.of(
            "resourceId", resourceId,
            "eventId", eventId));
    }

    private Map<String, Object> toPugModel(CalendarResourceMessageDTO calendarResourceMessageDTO,
                                           String resourceName,
                                           Locale locale,
                                           ZoneId zoneToDisplay,
                                           boolean approved,
                                           I18NTranslator translator) {
        VEvent vEvent = GET_FIRST_VEVENT_FUNCTION.apply(calendarResourceMessageDTO.ics());

        ImmutableMap.Builder<String, Object> contentBuilder = ImmutableMap.builder();
        contentBuilder.put("event", toPugModel(vEvent, locale, zoneToDisplay))
            .put("resourceName", resourceName);

        ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();
        if (approved) {
            contentBuilder.put("approved", true);
            builder.put("subject.resourceStatus", translator.get(PartStat.ACCEPTED.getValue().toLowerCase()));
        } else {
            contentBuilder.put("approved", false);
            builder.put("subject.resourceStatus", translator.get(PartStat.DECLINED.getValue().toLowerCase()));
        }

        return builder.put("content", contentBuilder.build())
            .put("subject.resourceName", resourceName)
            .build();
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
            .put("hasResources", !resourceList.isEmpty())
            .put("resources", resourceList.stream()
                .collect(ImmutableMap.toImmutableMap(resource -> resource.email().asString(),
                    resource -> PERSON_TO_MODEL.apply(resource).toPugModel())));
        EventParseUtils.getEndTime(vEvent).map(endDate -> new EventTimeModel(endDate).toPugModel(locale, zoneToDisplay))
            .ifPresent(model -> eventBuilder.put("end", model));
        EventParseUtils.getLocation(vEvent).ifPresent(location -> eventBuilder.put("location", new LocationModel(location).toPugModel()));
        EventParseUtils.getDescription(vEvent).ifPresent(description -> eventBuilder.put("description", description));
        EventParseUtils.getPropertyValueIgnoreCase(vEvent, "X-OPENPAAS-VIDEOCONFERENCE")
            .ifPresent(value -> eventBuilder.put("videoConferenceLink", value));

        return eventBuilder.build();
    }
}
