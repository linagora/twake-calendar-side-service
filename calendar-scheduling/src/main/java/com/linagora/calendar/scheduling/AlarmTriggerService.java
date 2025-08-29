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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.CalendarUtil;
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
import com.linagora.calendar.storage.SimpleSessionProvider;
import com.linagora.calendar.storage.configuration.resolver.AlarmSettingReader;
import com.linagora.calendar.storage.configuration.resolver.ConfigurationResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;
import com.linagora.calendar.storage.event.AlarmInstantFactory;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.event.EventParseUtils;
import com.linagora.calendar.storage.exception.DomainNotFoundException;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class AlarmTriggerService {

    public static final Function<EventFields.Person, PersonModel> PERSON_TO_MODEL =
        person -> new PersonModel(person.cn(), person.email().asString());

    public static final Function<Calendar, VEvent> GET_FIRST_VEVENT_FUNCTION =
        calendar -> calendar.getComponent(Component.VEVENT)
            .map(VEvent.class::cast)
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar event"));

    public static final boolean RECURRING = true;
    public static final TemplateType TEMPLATE_TYPE = new TemplateType("event-alarm");

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmTriggerService.class);

    private final AlarmEventDAO alarmEventDAO;
    private final Clock clock;
    private final MailSender.Factory mailSenderFactory;
    private final SimpleSessionProvider sessionProvider;
    private final SettingsBasedResolver settingsBasedResolver;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final AlarmInstantFactory alarmInstantFactory;
    private final MaybeSender maybeSender;
    private final MailAddress senderAddress;

    @Inject
    @Singleton
    public AlarmTriggerService(AlarmEventDAO alarmEventDAO,
                               Clock clock,
                               MailSender.Factory mailSenderFactory,
                               SimpleSessionProvider sessionProvider,
                               ConfigurationResolver configurationResolver,
                               MessageGenerator.Factory messageGeneratorFactory,
                               AlarmInstantFactory alarmInstantFactory,
                               MailTemplateConfiguration mailTemplateConfiguration) {
        this(alarmEventDAO, clock, mailSenderFactory, sessionProvider,
            SettingsBasedResolver.of(configurationResolver,
                Set.of(SettingsBasedResolver.LanguageSettingReader.INSTANCE, new AlarmSettingReader())),
            messageGeneratorFactory, alarmInstantFactory,
            mailTemplateConfiguration);
    }

    public AlarmTriggerService(AlarmEventDAO alarmEventDAO,
                               Clock clock,
                               MailSender.Factory mailSenderFactory,
                               SimpleSessionProvider sessionProvider,
                               SettingsBasedResolver settingsBasedResolver,
                               MessageGenerator.Factory messageGeneratorFactory,
                               AlarmInstantFactory alarmInstantFactory,
                               MailTemplateConfiguration mailTemplateConfiguration) {
        this.alarmEventDAO = alarmEventDAO;
        this.clock = clock;
        this.mailSenderFactory = mailSenderFactory;
        this.sessionProvider = sessionProvider;
        this.settingsBasedResolver = settingsBasedResolver;
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.alarmInstantFactory = alarmInstantFactory;
        this.maybeSender = mailTemplateConfiguration.sender();
        this.senderAddress = maybeSender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
    }

    public Mono<Void> sendMailAndCleanup(AlarmEvent alarmEvent) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return sendMail(alarmEvent, now)
            .then(cleanup(alarmEvent))
            .doOnSuccess(unused -> LOGGER.info("Processed alarm for event: {}, recipient: {}, eventStartTime: {}",
                alarmEvent.eventUid().value(), alarmEvent.recipient().asString(), alarmEvent.eventStartTime()));
    }

    private Mono<Void> cleanup(AlarmEvent alarmEvent) {
        if (alarmEvent.recurring()) {
            // If the event is recurring, we need to update the alarm time for the next occurrence
            return alarmInstantFactory.computeNextAlarmInstant(CalendarUtil.parseIcs(alarmEvent.ics()), Username.fromMailAddress(alarmEvent.recipient()))
                .map(alarmInstant -> Flux.fromIterable(alarmInstant.recipients())
                    .map(recipient -> new AlarmEvent(alarmEvent.eventUid(), alarmInstant.alarmTime(), alarmInstant.eventStartTime(),
                        RECURRING, alarmInstant.recurrenceId().map(DateProperty::getValue), recipient, alarmEvent.ics()))
                    .flatMap(alarmEventDAO::update)
                    .then())
                .orElse(alarmEventDAO.delete(alarmEvent.eventUid(), alarmEvent.recipient()));
        }
        return alarmEventDAO.delete(alarmEvent.eventUid(), alarmEvent.recipient());
    }

    private Mono<Void> sendMail(AlarmEvent alarmEvent, Instant now) {
        Username recipientUser = Username.fromMailAddress(alarmEvent.recipient());
        if (alarmEvent.eventStartTime().isBefore(now)) {
            // If the event start time is before now, we do not send the alarm
            return Mono.empty();
        }
        return getUserSettings(recipientUser)
            .filter(resolvedSettings -> resolvedSettings.get(ALARM_SETTING_IDENTIFIER, Boolean.class).orElse(ENABLE_ALARM))
            .flatMap(resolvedSettings -> {
                Locale locale = resolvedSettings.locale();
                Map<String, Object> model = toPugModel(CalendarUtil.parseIcs(alarmEvent.ics()),
                    alarmEvent.recurrenceId(),
                    locale,
                    Duration.between(now, alarmEvent.eventStartTime()));
                return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(locale), TEMPLATE_TYPE))
                    .flatMap(messageGenerator -> messageGenerator.generate(recipientUser, senderAddress, model, List.of()))
                    .flatMap(message -> mailSenderFactory.create()
                        .flatMap(mailSender -> mailSender.send(new Mail(maybeSender, List.of(alarmEvent.recipient()), message))));
            });
    }

    private Mono<ResolvedSettings> getUserSettings(Username user) {
        return settingsBasedResolver.readSavedSettings(sessionProvider.createSession(user))
            .defaultIfEmpty(ResolvedSettings.DEFAULT)
            .doOnError(error -> {
                if (!(error instanceof DomainNotFoundException)) {
                    LOGGER.error("Error resolving user settings for {}, will use default settings: {}",
                        user.asString(), ResolvedSettings.DEFAULT, error);
                }
            }).onErrorResume(error -> Mono.just(ResolvedSettings.DEFAULT));
    }

    private Map<String, Object> toPugModel(Calendar calendar,
                                           Optional<String> maybeRecurrenceId,
                                           Locale locale,
                                           Duration duration) {
        VEvent vEvent = maybeRecurrenceId.flatMap(recurrenceId -> getVEvent(calendar, recurrenceId))
            .orElse(GET_FIRST_VEVENT_FUNCTION.apply(calendar));

        return toPugModel(vEvent, duration, locale);
    }

    private Optional<VEvent> getVEvent(Calendar calendar, String recurrenceId) {
        return calendar.getComponents(Component.VEVENT)
            .stream()
            .map(VEvent.class::cast)
            .filter(event -> event.getProperty(Property.RECURRENCE_ID).map(Property::getValue).map(recurrenceId::equals).orElse(false))
            .findAny();
    }

    private Map<String, Object> toPugModel(VEvent vEvent, Duration duration, Locale locale) {
        PersonModel organizer = PERSON_TO_MODEL.apply(EventParseUtils.getOrganizer(vEvent));
        String summary = EventParseUtils.getSummary(vEvent).orElse(StringUtils.EMPTY);
        List<PersonModel> attendees = EventParseUtils.getAttendees(vEvent).stream()
            .map(PERSON_TO_MODEL)
            .toList();
        List<PersonModel> resources = EventParseUtils.getResources(vEvent).stream()
            .map(PERSON_TO_MODEL)
            .toList();
        Optional<String> location = EventParseUtils.getLocation(vEvent);
        Optional<String> description = EventParseUtils.getDescription(vEvent);
        Optional<String> videoConf = EventParseUtils.getPropertyValueIgnoreCase(vEvent, "X-OPENPAAS-VIDEOCONFERENCE");

        return AlarmContentModelBuilder.builder()
            .duration(duration)
            .summary(summary)
            .location(location)
            .organizer(organizer)
            .attendees(attendees)
            .resources(resources)
            .description(description)
            .videoconference(videoConf)
            .locale(locale)
            .buildAsMap();
    }
}
