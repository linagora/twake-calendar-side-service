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

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import org.apache.james.core.Username;
import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;

import com.github.fge.lambdas.Throwing;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportResult;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportType;
import com.linagora.calendar.restapi.RestApiConfiguration;
import com.linagora.calendar.smtp.template.InlinedAttachment;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.MailTemplateConfiguration;
import com.linagora.calendar.smtp.template.MessageGenerator;
import com.linagora.calendar.smtp.Mail;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class ImportMailReportRender {
    private final MailTemplateConfiguration templateConfiguration;
    private final MessageGenerator.Factory messageGeneratorFactory;
    private final RestApiConfiguration configuration;
    private final byte[] calendarLogo;
    private final byte[] contactsLogo;

    @Inject
    @Singleton
    public ImportMailReportRender(MailTemplateConfiguration templateConfiguration, MessageGenerator.Factory factory, RestApiConfiguration configuration,
                                  @Named("calendar-logo") byte[] calendarLogo, @Named("calendar-logo") byte[] contactLogo) {
        this.templateConfiguration = templateConfiguration;
        this.messageGeneratorFactory = factory;
        this.configuration = configuration;
        this.calendarLogo = calendarLogo;
        this.contactsLogo = contactLogo;
    }

    public Mono<Mail> generateMail(ImportType importType, ImportResult importResult, Username username) {
        String baseUrl = switch (importType) {
            case ICS -> configuration.getCalendarSpaUrl().toString();
            case VCARD -> configuration.getCalendarSpaUrl().toString(); // TODO change me to point to contact SPA spa.contacts.url
        };
        byte[] logoBytes = switch (importType) {
            case ICS -> calendarLogo;
            case VCARD -> contactsLogo;
        };

        Map<String, Object> model = ImmutableMap.of(
            "content", Map.of(
                "baseUrl", baseUrl,
                "jobFailedList", importResult.failed(),
                "jobSucceedCount", importResult.succeedCount(),
                "jobFailedCount", importResult.failed().size()));
        InlinedAttachment logoAttachment = new InlinedAttachment(ContentType.of("image/png"), Cid.from("logo"), logoBytes, "logo.png");

        return Mono.fromCallable(() -> messageGeneratorFactory.forLocalizedFeature(new Language("en"), importType.getTemplateType()))
            .flatMap(messageGenerator -> messageGenerator.generate(username, model, ImmutableList.of(logoAttachment)))
            .map(Throwing.function(message -> new Mail(templateConfiguration.sender(), List.of(username.asMailAddress()), message)))
            .subscribeOn(Schedulers.boundedElastic());
    }
}

