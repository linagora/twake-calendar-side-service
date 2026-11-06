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

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.Temporal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.dav.importer.ContactToImport;
import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.smtp.template.TemplateType;
import com.linagora.calendar.storage.AddressBookURL;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.model.ImportId;
import com.linagora.calendar.storage.model.UploadedFile;

import ezvcard.VCard;
import ezvcard.property.SimpleProperty;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateProperty;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
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

        public String asString() {
            return this.templateType.value();
        }
    }

    public record ImportCommand(ImportId importId,
                                ImportType importType,
                                byte[] uploadData,
                                OpenPaaSId baseId,
                                String resourceId) {

        public static ImportCommand create(ImportType importType,
                                           UploadedFile uploadedFile,
                                           String baseId,
                                           String resourceId) {
            return new ImportCommand(ImportId.generate(),
                importType,
                uploadedFile.data(),
                new OpenPaaSId(baseId),
                resourceId);
        }

        public URI importURI() {
            return switch (importType) {
                case ICS -> new CalendarURL(baseId(), new OpenPaaSId(resourceId)).asUri();
                case VCARD -> new AddressBookURL(baseId(), resourceId).asUri();
            };
        }
    }

    public interface ImportToDavHandler {
        Mono<ImportResult> handle(ImportCommand importCommand, Username username);
    }

    static class ImportICSToDavHandler implements ImportToDavHandler {
        private final CalDavClient calDavClient;

        ImportICSToDavHandler(CalDavClient calDavClient) {
            this.calDavClient = calDavClient;
        }

        @Override
        public Mono<ImportResult> handle(ImportCommand importCommand, Username username) {
            CalendarURL calendarURL = new CalendarURL(importCommand.baseId(), new OpenPaaSId(importCommand.resourceId()));

            return Mono.fromCallable(() -> EventToImport.parse(importCommand.uploadData()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(event -> Mono.defer(() -> calDavClient.importCalendar(calendarURL, event.resourceName(), username, event.ics()))
                    .thenReturn(ImportResult.succeed())
                    .onErrorResume(error -> {
                        LOGGER.error("Error importing event with UID {}: {}", event.uid(), error.getMessage());
                        return Mono.just(ImportResult.failed(failedItemFromEvent(event)));
                    }), DEFAULT_CONCURRENCY)
                .reduce(ImportResult::reduce)
                .defaultIfEmpty(new ImportResult(0, ImmutableList.of()));
        }

        static ImportResult.FailedItem failedItemFromEvent(EventToImport event) {
            VEvent vEvent = event.vEvents().getFirst();

            return new ImportResult.FailedItem(
                ImmutableMap.of(
                    "uid", event.uid(),
                    "summary", Optional.ofNullable(vEvent.getSummary()).map(Property::getValue).orElse(StringUtils.EMPTY),
                    "start", readableDate(event, "start", () -> Optional.ofNullable(vEvent.getDateTimeStart())),
                    "end", readableDate(event, "end", vEvent::getEndDate)));
        }

        private static String readableDate(EventToImport event, String field, Supplier<Optional<? extends DateProperty<?>>> dateProperty) {
            try {
                return dateProperty.get()
                    .map(EventDateFormatter::format)
                    .orElse(StringUtils.EMPTY);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not compute {} of rejected event with UID {} for the import report", field, event.uid(), e);
                return StringUtils.EMPTY;
            }
        }
    }

    static class EventDateFormatter {
        private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        private static final String UTC = "UTC";
        private static final Pattern LINE_BREAK = Pattern.compile("[\\r\\n]");
        private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");
        private static final int MAX_ZONE_LABEL_LENGTH = 64;

        static String format(DateProperty<?> dateProperty) {
            Temporal temporal = dateProperty.getDate();
            return switch (temporal) {
                case LocalDate date -> DATE_FORMATTER.format(date);
                case Instant instant -> withZoneLabel(DATE_TIME_FORMATTER.format(instant.atOffset(ZoneOffset.UTC)), Optional.of(UTC));
                default -> withZoneLabel(DATE_TIME_FORMATTER.format(temporal), zoneLabel(dateProperty, temporal));
            };
        }

        private static Optional<String> zoneLabel(DateProperty<?> dateProperty, Temporal temporal) {
            return dateProperty.getParameter(Parameter.TZID)
                .map(Parameter::getValue)
                .flatMap(EventDateFormatter::sanitizeTzid)
                .or(() -> isUtc(temporal) ? Optional.of(UTC) : Optional.empty());
        }

        // A parameter line without a colon makes ical4j merge the next line into the TZID value
        private static Optional<String> sanitizeTzid(String rawTzid) {
            String firstLine = LINE_BREAK.split(rawTzid, 2)[0];
            String withoutControlCharacters = CONTROL_CHARACTERS.matcher(firstLine).replaceAll(StringUtils.EMPTY);
            return Optional.of(StringUtils.truncate(StringUtils.normalizeSpace(withoutControlCharacters), MAX_ZONE_LABEL_LENGTH))
                .map(String::trim)
                .filter(StringUtils::isNotEmpty);
        }

        private static boolean isUtc(Temporal temporal) {
            return switch (temporal) {
                case ZonedDateTime zonedDateTime -> zonedDateTime.getOffset().equals(ZoneOffset.UTC);
                case OffsetDateTime offsetDateTime -> offsetDateTime.getOffset().equals(ZoneOffset.UTC);
                default -> false;
            };
        }

        private static String withZoneLabel(String dateTime, Optional<String> zoneLabel) {
            return zoneLabel.map(label -> dateTime + " " + label)
                .orElse(dateTime);
        }
    }

    static class ImportVCardToDavHandler implements ImportToDavHandler {
        private final CardDavClient cardDavClient;

        ImportVCardToDavHandler(CardDavClient cardDavClient) {
            this.cardDavClient = cardDavClient;
        }

        @Override
        public Mono<ImportResult> handle(ImportCommand importCommand, Username username) {
            AddressBookURL addressBookURL = new AddressBookURL(importCommand.baseId(), importCommand.resourceId());

            return Mono.fromCallable(() -> ContactToImport.parse(importCommand.uploadData()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(contact -> cardDavClient.upsertContact(username, addressBookURL, contact.resourceName(), contact.payload())
                    .thenReturn(ImportResult.succeed())
                    .onErrorResume(error -> {
                        LOGGER.error("Error importing contact with UID {}: {}", contact.resourceName(), error.getMessage());
                        return Mono.just(ImportResult.failed(failedItemFromVCard(contact.vcard())));
                    }), DEFAULT_CONCURRENCY)
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
    private final Set<ImportResultNotifier> importResultNotifiers;

    @Inject
    public ImportProcessor(CardDavClient cardDavClient,
                           CalDavClient calDavClient,
                           Set<ImportResultNotifier> importResultNotifiers) {
        this.importICSHandler = new ImportICSToDavHandler(calDavClient);
        this.importVCardHandler = new ImportVCardToDavHandler(cardDavClient);
        this.importResultNotifiers = importResultNotifiers;
    }

    public Mono<Void> process(ImportCommand importCommand, MailboxSession mailboxSession) {
        Username username = mailboxSession.getUser();

        LOGGER.debug("Starting import {} of type {} for user {}", importCommand.importId().value(), importCommand.importType().name(), username);
        ImportToDavHandler importToDavHandler = switch (importCommand.importType()) {
            case ICS -> importICSHandler;
            case VCARD -> importVCardHandler;
        };

        return importToDavHandler.handle(importCommand, username)
            .map(importResult -> (ImportExecutionResult) new ImportExecutionResult.Success(importResult))
            .onErrorResume(error -> {
                LOGGER.error("Import {} failed for user {}", importCommand.importId().value(), username, error);
                return Mono.just(new ImportExecutionResult.Failed(error));
            })
            .flatMap(importExecutionResult -> sendNotifications(importCommand, importExecutionResult, username));
    }

    private Mono<Void> sendNotifications(ImportCommand command, ImportExecutionResult result, Username username) {
        return Flux.fromIterable(importResultNotifiers)
            .flatMap(notifier ->
                notifier.notify(command, result, username)
                    .onErrorResume(error -> {
                        LOGGER.error("Import notification failed (notifier={}, importId={}, user={})",
                            notifier.getClass().getSimpleName(), command.importId().value(), username.asString(), error);
                        return Mono.empty();
                    }))
            .then();
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

    public sealed interface ImportExecutionResult permits ImportExecutionResult.Success, ImportExecutionResult.Failed {
        record Success(ImportResult result) implements ImportExecutionResult {
        }

        record Failed(Throwable error) implements ImportExecutionResult {
        }
    }
}
