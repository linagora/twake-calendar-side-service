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

import static reactor.core.scheduler.Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.james.core.Username;
import org.apache.james.events.EventBus;
import org.apache.james.events.RegistrationKey;

import com.linagora.calendar.restapi.routes.ImportProcessor.ImportCommand;
import com.linagora.calendar.restapi.routes.ImportProcessor.ImportExecutionResult;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.AddressBookURLRegistrationKey;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.CalendarURLRegistrationKey;
import com.linagora.calendar.storage.ImportEvent;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedResolver;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public interface ImportResultNotifier {

    Mono<Void> notify(ImportCommand importCommand, ImportExecutionResult importExecutionResult, Username username);

    class ImportWebSocketNotifier implements ImportResultNotifier {

        private final EventBus eventBus;

        @Inject
        public ImportWebSocketNotifier(EventBus eventBus) {
            this.eventBus = eventBus;
        }

        @Override
        public Mono<Void> notify(ImportCommand importCommand, ImportExecutionResult importExecutionResult, Username username) {
            ImportEvent event = buildImportEvent(importCommand, importExecutionResult);
            return eventBus.dispatch(event, registrationKey(importCommand))
                .then();
        }

        private RegistrationKey registrationKey(ImportCommand importCommand) {
            return switch (importCommand.importType()) {
                case ICS ->
                    new CalendarURLRegistrationKey(new CalendarURL(importCommand.baseId(), new OpenPaaSId(importCommand.resourceId())));
                case VCARD ->
                    new AddressBookURLRegistrationKey(new AddressBookURL(importCommand.baseId(), importCommand.resourceId()));
            };
        }

        private ImportEvent buildImportEvent(ImportCommand importCommand,
                                             ImportExecutionResult importExecutionResult) {
            return switch (importExecutionResult) {
                case ImportExecutionResult.Success success -> ImportEvent.succeeded(importCommand.importId(),
                    importCommand.importURI(),
                    importCommand.importType().asString(),
                    success.result().succeedCount(),
                    success.result().failed().size());

                case ImportExecutionResult.Failed failed -> ImportEvent.failed(importCommand.importId(),
                    importCommand.importURI(), importCommand.importType().asString());
            };
        }
    }

    class SendMailNotifier implements ImportResultNotifier {
        private final SettingsBasedResolver settingsResolver;
        private final ImportMailReportRender mailReportRender;
        private final MailSender.Factory mailSenderFactory;
        private final Scheduler mailScheduler;

        @Inject
        public SendMailNotifier(@Named("language") SettingsBasedResolver settingsResolver,
                                ImportMailReportRender mailReportRender,
                                MailSender.Factory mailSenderFactory) {
            this.settingsResolver = settingsResolver;
            this.mailReportRender = mailReportRender;
            this.mailSenderFactory = mailSenderFactory;
            this.mailScheduler = Schedulers.newBoundedElastic(1, DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
                "sendMailScheduler");
        }

        @Override
        public Mono<Void> notify(ImportCommand importCommand, ImportExecutionResult importExecutionResult, Username username) {
            if (importExecutionResult instanceof ImportExecutionResult.Failed) {
                return Mono.empty();
            }

            ImportProcessor.ImportResult importResult = ((ImportExecutionResult.Success) importExecutionResult).result();
            return settingsResolver.resolveOrDefault(username)
                .map(SettingsBasedResolver.ResolvedSettings::locale)
                .flatMap(locale -> sendReportMail(importCommand.importType(), new Language(locale), importResult, username))
                .then();
        }

        private Mono<Void> sendReportMail(ImportProcessor.ImportType importType, Language language, ImportProcessor.ImportResult importResult, Username receiver) {
            return mailReportRender.generateMail(importType, language, importResult, receiver)
                .flatMap(mail -> mailSenderFactory.create().flatMap(mailSender -> mailSender.send(mail)))
                .subscribeOn(mailScheduler);
        }
    }
}
