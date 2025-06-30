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

import static org.apache.james.util.ReactorUtils.DEFAULT_CONCURRENCY;
import static reactor.core.scheduler.Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.smtp.MailSender;
import com.linagora.calendar.smtp.template.Language;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.configuration.resolver.SettingsBasedLocator;
import com.linagora.calendar.storage.model.UploadedFile;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.property.SimpleProperty;
import ezvcard.property.Uid;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

public class ImportProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportProcessor.class);

    public enum ImportType {
        ICS(new TemplateType("import-calendar")),
        VCARD(new TemplateType("import-contacts"));

        private final TemplateType templateType;

        ImportType(TemplateType templateType) {
            this.templateType = templateType;
        }

        public TemplateType getTemplateType() {
            return templateType;
        }
    }

    public interface ImportToDavHandler {
        Mono<ImportResult> handle(UploadedFile uploadedFile, Username username, OpenPaaSId baseId, String resourceId);
    }

    static class ImportICSToDavHandler implements ImportToDavHandler {
        private final CalDavClient calDavClient;

        ImportICSToDavHandler(CalDavClient calDavClient) {
            this.calDavClient = calDavClient;
        }

        @Override
        public Mono<ImportResult> handle(UploadedFile uploadedFile, Username username, OpenPaaSId baseId, String resourceId) {
            CalendarURL calendarURL = new CalendarURL(baseId, new OpenPaaSId(resourceId));

            return Mono.fromCallable(() -> CalendarUtil.parseIcs(uploadedFile.data()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(calendar -> Flux.fromIterable(calendar.getComponents(Component.VEVENT))
                    .cast(VEvent.class)
                    .groupBy(vEvent -> vEvent.getProperty(Property.UID).get().getValue())
                    .flatMap(groupedFlux -> groupedFlux.collectList().flatMap(vEvents -> {
                        Calendar combinedCalendar = new Calendar();
                        vEvents.forEach(combinedCalendar::add);

                        String eventId = groupedFlux.key();
                        byte[] bytes = combinedCalendar.toString().getBytes(StandardCharsets.UTF_8);

                        return calDavClient.importCalendar(calendarURL, eventId, username, bytes)
                            .thenReturn(ImportResult.succeed())
                            .onErrorResume(error -> {
                                LOGGER.error("Error importing event with UID {}: {}", eventId, error.getMessage());
                                return Mono.just(ImportResult.failed(failedItemFromVEvent(vEvents.getFirst(), eventId)));
                            });
                    }), DEFAULT_CONCURRENCY))
                .reduce(ImportResult::reduce)
                .defaultIfEmpty(new ImportResult(0, ImmutableList.of()));
        }

        private ImportResult.FailedItem failedItemFromVEvent(VEvent vEvent, String eventUid) {
            return new ImportResult.FailedItem(
                ImmutableMap.of(
                    "uid", eventUid,
                    "summary", Optional.ofNullable(vEvent.getSummary()).map(Property::getValue).orElse(StringUtils.EMPTY),
                    "start", Optional.ofNullable(vEvent.getDateTimeStart()).map(Property::toString).orElse(StringUtils.EMPTY),
                    "end", Optional.ofNullable(vEvent.getEndDate()).map(Object::toString).orElse(StringUtils.EMPTY)));
        }
    }

    static class ImportVCardToDavHandler implements ImportToDavHandler {
        private final CardDavClient cardDavClient;

        ImportVCardToDavHandler(CardDavClient cardDavClient) {
            this.cardDavClient = cardDavClient;
        }

        @Override
        public Mono<ImportResult> handle(UploadedFile uploadedFile, Username username, OpenPaaSId baseId, String resourceId) {
            return Mono.fromCallable(() -> Ezvcard.parse(new String(uploadedFile.data(), StandardCharsets.UTF_8)).all())
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(vcard -> {
                    String vcardUid = UUID.randomUUID().toString();
                    vcard.setUid(new Uid(vcardUid));

                    String vcardString = Ezvcard.write(vcard)
                        .prodId(false)
                        .go();

                    return cardDavClient.createContact(
                            username, baseId, resourceId, vcardUid, vcardString.getBytes(StandardCharsets.UTF_8))
                        .thenReturn(ImportResult.succeed())
                        .onErrorResume(error -> {
                            LOGGER.error("Error importing contact with UID {}: {}", vcardUid, error.getMessage());
                            return Mono.just(ImportResult.failed(failedItemFromVCard(vcard)));
                        });
                }, DEFAULT_CONCURRENCY)
                .reduce(ImportResult::reduce)
                .defaultIfEmpty(new ImportResult(0, ImmutableList.of()));
        }

        private ImportResult.FailedItem failedItemFromVCard(VCard vcard) {
            return new ImportResult.FailedItem(
                ImmutableMap.of(
                    "email", Optional.ofNullable(vcard.getEmails())
                        .filter(emails -> !emails.isEmpty())
                        .map(List::getFirst)
                        .map(SimpleProperty::getValue)
                        .orElse(StringUtils.EMPTY)));
        }
    }

    private final ImportICSToDavHandler importICSHandler;
    private final ImportVCardToDavHandler importVCardHandler;
    private final Scheduler mailScheduler;
    private final MailSender.Factory mailSenderFactory;
    private final ImportMailReportRender mailReportRender;
    private final SettingsBasedLocator settingsBasedLocator;

    @Inject
    public ImportProcessor(CardDavClient cardDavClient,
                           CalDavClient calDavClient,
                           MailSender.Factory mailSenderFactory,
                           ImportMailReportRender mailReportRender,
                           SettingsBasedLocator settingsBasedLocator) {
        this.importICSHandler = new ImportICSToDavHandler(calDavClient);
        this.importVCardHandler = new ImportVCardToDavHandler(cardDavClient);
        this.mailScheduler = Schedulers.newBoundedElastic(1, DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
            "sendMailScheduler");
        this.mailSenderFactory = mailSenderFactory;
        this.mailReportRender = mailReportRender;
        this.settingsBasedLocator = settingsBasedLocator;
    }

    public Mono<Void> process(ImportType importType, UploadedFile uploadedFile,
                              OpenPaaSId baseId, String resourceId, MailboxSession mailboxSession) {
        Username username = mailboxSession.getUser();

        ImportToDavHandler importToDavHandler = switch (importType) {
            case ICS -> importICSHandler;
            case VCARD -> importVCardHandler;
        };

        return importToDavHandler.handle(uploadedFile, username, baseId, resourceId)
            .flatMap(importResult -> settingsBasedLocator.getLanguageUserSetting(mailboxSession)
                .doOnSuccess(language -> sendReportMail(importType, new Language(language), importResult, username)))
            .then();
    }

    private Disposable sendReportMail(ImportType importType, Language language, ImportResult importResult, Username receiver) {
        return mailReportRender.generateMail(importType, language, importResult, receiver)
            .flatMap(mail -> mailSenderFactory.create().flatMap(mailSender -> mailSender.send(mail)))
            .doOnError(error -> LOGGER.error("Error sending import `{}` report mail to {}: {}", importType.name(), receiver, error.getMessage()))
            .subscribeOn(mailScheduler)
            .subscribe();
    }

    public record ImportResult(int succeedCount, ImmutableList<FailedItem> failed) {

        public record FailedItem(Map<String, Object> keyValues) {
        }

        public ImportResult {
            Preconditions.checkArgument(succeedCount >= 0, "succeedCount must not be negative");
            Preconditions.checkArgument(failed != null, "failed must not be null");
        }

        public static ImportResult succeed() {
            return new ImportResult(1, ImmutableList.of());
        }

        public static ImportResult failed(FailedItem failedItem) {
            return new ImportResult(0, ImmutableList.of(failedItem));
        }

        public ImportResult reduce(ImportResult other) {
            return new ImportResult(this.succeedCount + other.succeedCount,
                ImmutableList.<FailedItem>builder()
                    .addAll(this.failed)
                    .addAll(other.failed)
                    .build());
        }
    }
}
