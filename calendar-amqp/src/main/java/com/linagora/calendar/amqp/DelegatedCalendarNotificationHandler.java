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

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.List;
import java.util.Map;

import jakarta.inject.Named;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.core.Username;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.dom.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import com.linagora.calendar.amqp.CalendarDelegatedNotificationConsumer.CalendarDelegatedCreatedMessage;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.smtp.EventEmailFilter;
import com.linagora.calendar.smtp.Mail;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.template.MimeAttachment;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver.ResolvedSettings;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class DelegatedCalendarNotificationHandler {
    static class CalendarDelegatedNotificationHandlerException extends RuntimeException {
        public CalendarDelegatedNotificationHandlerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DelegatedCalendarNotificationHandler.class);
    private static final TemplateType MAIL_TEMPLATE = new TemplateType("calendar-delegate-created");
    private static final String DELEGATED_SOURCE_PROPERTY = "calendarserver:delegatedsource";
    private static final String CALENDAR_NAME_PROPERTY = "dav:name";

    private final OpenPaaSUserDAO openPaaSUserDAO;
    private final CalDavClient calDavClient;
    private final SettingsBasedResolver settingsResolver;
    private final MailSender.Factory mailSenderFactory;
    private final MaybeSender maybeSender;
    private final MailAddress senderAddress;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final EventEmailFilter eventEmailFilter;
    private final URI spaCalendarBaseUrl;
    private final MimeAttachment logoAttachment;

    @Inject
    public DelegatedCalendarNotificationHandler(OpenPaaSUserDAO openPaaSUserDAO,
                                                CalDavClient calDavClient,
                                                @Named("language") SettingsBasedResolver settingsResolver,
                                                MailSender.Factory mailSenderFactory,
                                                MailTemplateConfiguration mailTemplateConfiguration,
                                                MessageGenerator.Factory messageGeneratorFactory,
                                                EventEmailFilter eventEmailFilter,
                                                @Named("spaCalendarUrl") URL calendarBaseUrl,
                                                @Named("calendar-logo") byte[] calendarLogo) throws URISyntaxException {
        this.openPaaSUserDAO = openPaaSUserDAO;
        this.calDavClient = calDavClient;
        this.settingsResolver = settingsResolver;
        this.mailSenderFactory = mailSenderFactory;
        this.maybeSender = mailTemplateConfiguration.sender();
        this.messageGeneratorFactory = messageGeneratorFactory;
        this.eventEmailFilter = eventEmailFilter;
        this.spaCalendarBaseUrl = calendarBaseUrl.toURI();
        this.senderAddress = maybeSender.asOptional()
            .orElseThrow(() -> new IllegalArgumentException("Sender address must not be empty"));
        this.logoAttachment = MimeAttachment.builder()
            .contentType(ContentType.of("image/png"))
            .cid(Cid.from("<logo>"))
            .content(calendarLogo)
            .fileName("logo.png")
            .build();
    }

    public record DelegatedCalendarNotificationData(OpenPaaSUser delegatorUser,
                                                    CalendarURL originalCalendarURL,
                                                    String originalCalendarName,
                                                    OpenPaaSUser delegatedUser,
                                                    CalendarURL delegatedCalendarURL,
                                                    String rightKey) {

        public Map<String, Object> toPugModel(URI spaCalendarBaseUrl) {
            return ImmutableMap.of(
                "content", ImmutableMap.of(
                    "delegatorName", delegatorUser.fullName(),
                    "calendarName", originalCalendarName,
                    "calendarUrl", spaCalendarBaseUrl.toASCIIString()),
                "delegatorName", delegatorUser.fullName(),
                "calendarName", originalCalendarName);
        }
    }

    private record CalendarMetadata(OpenPaaSUser owner, JsonNode metadata) {
    }

    public Mono<Void> handle(CalendarDelegatedCreatedMessage createdMessage) {
        return buildNotificationData(createdMessage)
            .filter(Throwing.predicate(notificationData -> eventEmailFilter.shouldProcess(notificationData.delegatedUser().username().asMailAddress())))
            .flatMap(this::processNotification);
    }

    public Mono<Void> processNotification(DelegatedCalendarNotificationData notificationData) {
        Username recipientUser = notificationData.delegatedUser().username();

        return settingsResolver.resolveOrDefault(recipientUser)
            .flatMap(resolvedSettings -> generateMessage(notificationData, resolvedSettings))
            .flatMap(mailMessage -> mailSenderFactory.create()
                .flatMap(mailSender -> mailSender.send(new Mail(maybeSender, ImmutableList.of(Throwing.supplier(recipientUser::asMailAddress).get()), mailMessage)))
                .onErrorMap(error -> new CalendarDelegatedNotificationHandlerException("Failed to send delegated calendar notification email", error)))
            .doOnSuccess(any -> LOGGER.debug("Consumed delegated calendar notification for user {} on calendar {}", notificationData.delegatedUser().username(), notificationData.originalCalendarURL()));
    }

    private Mono<Message> generateMessage(DelegatedCalendarNotificationData notificationData, ResolvedSettings resolvedSettings) {
        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language(resolvedSettings.locale()), MAIL_TEMPLATE))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(messageGenerator -> {
                Username recipientUser = notificationData.delegatedUser().username();
                return messageGenerator.generate(recipientUser, senderAddress, notificationData.toPugModel(spaCalendarBaseUrl),
                    List.of(logoAttachment));
            })
            .onErrorResume(error -> Mono.error(new CalendarDelegatedNotificationHandlerException("Failed to generate delegated calendar notification email", error)));
    }

    public Mono<DelegatedCalendarNotificationData> buildNotificationData(CalendarDelegatedCreatedMessage message) {
        LOGGER.debug("Enriching delegated calendar notification for message: {}", message);
        CalendarURL delegatedCalendarURL = message.calendarURL();

        return fetchCalendarWithOwner(delegatedCalendarURL)
            .filter(result -> result.metadata().has(DELEGATED_SOURCE_PROPERTY))
            .flatMap(delegated -> {
                LOGGER.debug("Delegated calendar detected for calendar {}, delegated user={}", delegatedCalendarURL.serialize(), delegated.owner().username());
                CalendarURL originalCalendarURL = CalendarURL.parse(delegated.metadata().get(DELEGATED_SOURCE_PROPERTY).asText());
                OpenPaaSUser delegatedUser = delegated.owner();

                return fetchCalendarWithOwner(originalCalendarURL)
                    .map(original -> {
                        String originalCalendarName = original.metadata().path(CALENDAR_NAME_PROPERTY).asText();
                        return new DelegatedCalendarNotificationData(
                            original.owner(),
                            originalCalendarURL,
                            originalCalendarName,
                            delegatedUser,
                            delegatedCalendarURL,
                            message.rightKey().orElse(null));
                    });
            });
    }

    private Mono<CalendarMetadata> fetchCalendarWithOwner(CalendarURL calendarURL) {
        return openPaaSUserDAO.retrieve(calendarURL.base())
            .flatMap(user -> calDavClient.fetchCalendarMetadata(user.username(), calendarURL)
                .map(metadata -> new CalendarMetadata(user, metadata)))
            .doOnError(e -> LOGGER.error("Failed to resolve owner or metadata for calendar {}", calendarURL.serialize(), e));
    }
}
