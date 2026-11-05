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

package com.linagora.calendar.scheduling;

import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ALARM_SETTING_IDENTIFIER;
import static com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader.ENABLE_ALARM;
import static com.linagora.calendar.storage.event.EventParseUtils.DuplicateAttendeePolicy.KEEP_FIRST;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.events.Event;
import org.apache.james.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.api.CalendarUtil;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.smtp.template.content.model.AlarmContentModelBuilder;
import com.linagora.calendar.smtp.template.content.model.PersonModel;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.EventBusAlarmEvent;
import com.linagora.calendar.storage.UsernameRegistrationKey;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.event.AlarmAction;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class AlarmTriggerService {

    public static final Function<Calendar, VEvent> GET_FIRST_VEVENT_FUNCTION =
        calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));

    public static final TemplateType TEMPLATE_TYPE = new TemplateType("event-alarm");

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmTriggerService.class);

    private final AlarmEventDAO alarmEventDAO;
    private final Clock clock;
    private final MailSender.Factory mailSenderFactory;
    private final SettingsBasedResolver settingsResolver;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final AlarmInstantFactory alarmInstantFactory;
    private final MaybeSender maybeSender;
    private final MailAddress senderAddress;
    private final EventBus eventBus;

    @Inject
    @Singleton
    public AlarmTriggerService(AlarmEventDAO alarmEventDAO,
                               Clock clock,
                               MailSender.Factory mailSenderFactory,
                               @Named("alarm") SettingsBasedResolver settingsResolver,
                               MessageGenerator.Factory messageGeneratorFactory,
                               AlarmInstantFactory alarmInstantFactory,
                               MailTemplateConfiguration mailTemplateConfiguration,
                               EventBus eventBus) {
        this.alarmEventDAO = alarmEventDAO;
        this.clock = clock;
        this.mailSenderFactory = mailSenderFactory;
        this.settingsResolver = settingsResolver;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.alarmInstantFactory = alarmInstantFactory;
        this.maybeSender = mailTemplateConfiguration.sender();
        this.senderAddress = maybeSender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.eventBus = eventBus;
    }

    public Mono<Void> sendAlarmAndCleanup(AlarmEvent alarmEvent) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return Mono.fromCallable(() -> CalendarUtil.parseIcs(alarmEvent.ics()))
            // Only parsing failures should drop the alarm; downstream failures must remain retryable.
            .onErrorResume(error -> {
                LOGGER.warn("Unparseable ICS for alarm of event {}, recipient: {}, dropping it",
                    alarmEvent.eventUid().value(), alarmEvent.recipient().asString(), error);
                return alarmEventDAO.delete(alarmEvent.eventUid(), alarmEvent.recipient()).then(Mono.empty());
            })
            .flatMap(calendar -> sendAlarm(alarmEvent, now, calendar)
                .then(cleanup(alarmEvent, calendar))
                .doOnSuccess(unused -> LOGGER.info("Processed alarm for event: {}, recipient: {}, action: {}, eventStartTime: {}",
                    alarmEvent.eventUid().value(), alarmEvent.recipient().asString(), alarmEvent.action(), alarmEvent.eventStartTime())));
    }

    private Mono<Void> sendAlarm(AlarmEvent alarmEvent, Instant now, Calendar calendar) {
        if (alarmEvent.eventStartTime().isBefore(now)) {
            // If the event start time is before now, we do not send the alarm
            return Mono.empty();
        }
        if (alarmEvent.action() == AlarmAction.DISPLAY) {
            return sendDisplayNotification(alarmEvent, calendar);
        }
        return sendMail(alarmEvent, now, calendar);
    }

    private Mono<Void> sendDisplayNotification(AlarmEvent alarmEvent, Calendar calendar) {
        Username recipientUser = Username.fromMailAddress(alarmEvent.recipient());
        VEvent vEvent = alarmedVEvent(calendar, alarmEvent);
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);

        EventBusAlarmEvent eventBusAlarmEvent = new EventBusAlarmEvent(
            Event.EventId.random(),
            recipientUser,
            summary,
            alarmEvent.eventPath(),
            alarmEvent.eventStartTime());

        UsernameRegistrationKey registrationKey = new UsernameRegistrationKey(recipientUser);
        return Mono.from(eventBus.dispatch(eventBusAlarmEvent, registrationKey));
    }

    private Mono<Void> cleanup(AlarmEvent alarmEvent, Calendar calendar) {
        Username username = Username.fromMailAddress(alarmEvent.recipient());
        Instant sinceInstant = alarmEvent.alarmTime();

        return Mono.fromCallable(() -> alarmInstantFactory.computeNextAlarmInstant(calendar, username, Optional.of(sinceInstant)))
            .flatMap(alarmInstantOptional -> alarmInstantOptional
                .map(alarmInstant -> alarmEventDAO.update(alarmEvent.withNextOccurrence(alarmInstant)))
                .orElseGet(() -> alarmEventDAO.delete(alarmEvent.eventUid(), alarmEvent.recipient())));
    }

    private Mono<Void> sendMail(AlarmEvent alarmEvent, Instant now, Calendar calendar) {
        Username recipientUser = Username.fromMailAddress(alarmEvent.recipient());
        return settingsResolver.resolveOrDefault(recipientUser)
            .filter(resolvedSettings -> resolvedSettings.get(ALARM_SETTING_IDENTIFIER, Boolean.class).orElse(ENABLE_ALARM))
            .flatMap(resolvedSettings -> {
                Locale locale = resolvedSettings.locale();
                Map<String, Object> model = toPugModel(alarmedVEvent(calendar, alarmEvent),
                    Duration.between(now, alarmEvent.eventStartTime()),
                    locale);
                return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(locale), TEMPLATE_TYPE))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(messageGenerator -> messageGenerator.generate(recipientUser, senderAddress, model, List.of()))
                    .flatMap(message -> mailSenderFactory.send(new Mail(maybeSender, List.of(alarmEvent.recipient()), message)));
            });
    }

    private VEvent alarmedVEvent(Calendar calendar, AlarmEvent alarmEvent) {
        return alarmEvent.recurrenceId()
            .flatMap(recurrenceId -> getVEvent(calendar, recurrenceId, alarmEvent.eventStartTime()))
            .orElse(GET_FIRST_VEVENT_FUNCTION.apply(calendar));
    }

    /**
     * Picks the override {@link AlarmInstantFactory} scheduled the alarm from. The alarm only holds the raw RECURRENCE-ID
     * value, which overrides of distinct zones can share while being distinct occurrences: its start tells them apart.
     */
    private Optional<VEvent> getVEvent(Calendar calendar, String recurrenceId, Instant eventStartTime) {
        List<VEvent> overrides = calendar.getComponents(Component.VEVENT)
            .stream()
            .map(VEvent.class::cast)
            .filter(event -> event.getProperty(Property.RECURRENCE_ID).map(Property::getValue).map(recurrenceId::equals).orElse(false))
            .toList();
        List<VEvent> startingAtAlarmedTime = overrides.stream()
            .filter(event -> startsAt(event, eventStartTime))
            .toList();
        return (startingAtAlarmedTime.isEmpty() ? overrides : startingAtAlarmedTime).stream()
            .reduce(AlarmInstantFactory.Default::selectLatestOverride);
    }

    private boolean startsAt(VEvent event, Instant instant) {
        return event.getProperty(Property.DTSTART)
            .flatMap(EventParseUtils::parseTimeAsInstant)
            .filter(instant::equals)
            .isPresent();
    }

    private Map<String, Object> toPugModel(VEvent vEvent, Duration duration, Locale locale) {
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        List<PersonModel> attendees = PersonModel.fromList(EventParseUtils.getAttendees(vEvent, KEEP_FIRST));
        List<PersonModel> resources = PersonModel.fromList(EventParseUtils.getResources(vEvent, KEEP_FIRST));
        Optional<String> location = EventParseUtils.getLocation(vEvent);
        Optional<String> description = EventParseUtils.getDescription(vEvent);
        Optional<String> videoConf = EventParseUtils.getPropertyValueIgnoreCase(vEvent, "X-OPENPAAS-VIDEOCONFERENCE");

        return AlarmContentModelBuilder.builder()
            .duration(duration)
            .summary(summary)
            .location(location)
            .organizer(PersonModel.from(EventParseUtils.getOrganizer(vEvent)))
            .attendees(attendees)
            .resources(resources)
            .description(description)
            .videoconference(videoConf)
            .locale(locale)
            .buildAsMap();
    }
}
