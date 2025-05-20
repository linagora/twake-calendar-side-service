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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.Endpoint;
import org.apache.james.jmap.http.Authenticator;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.metrics.api.MetricFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.lambdas.Throwing;
import com.google.api.client.util.Preconditions;
import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CalendarUtil;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.model.UploadedFile;
import com.linagora.calendar.storage.model.UploadedMimeType;

import ezvcard.Ezvcard;
import ezvcard.property.Uid;
import io.netty.handler.codec.http.HttpMethod;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

public class ImportRoute extends CalendarRoute {

    public static class ImportRequest {
        public String fileId;
        public String target;

        @JsonProperty(value = "fileId", required = true)
        public String getFileId() {
            return fileId;
        }

        @JsonProperty(value = "target", required = true)
        public String getTarget() {
            return target;
        }
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Logger LOGGER = LoggerFactory.getLogger(ImportRoute.class);

    private final UploadedFileDAO fileDAO;
    private final OpenPaaSUserDAO userDAO;
    private final CalDavClient calDavClient;
    private final CardDavClient cardDavClient;

    @Inject
    public ImportRoute(Authenticator authenticator, MetricFactory metricFactory,
                       UploadedFileDAO fileDAO, OpenPaaSUserDAO userDAO,
                       CalDavClient calDavClient, CardDavClient cardDavClient) {
        super(authenticator, metricFactory);
        this.fileDAO = fileDAO;
        this.userDAO = userDAO;
        this.calDavClient = calDavClient;
        this.cardDavClient = cardDavClient;
    }

    @Override
    Endpoint endpoint() {
        return new Endpoint(HttpMethod.POST, "/api/import");
    }

    @Override
    Mono<Void> handleRequest(HttpServerRequest request, HttpServerResponse response, MailboxSession session) {
        return request.receive().aggregate().asInputStream()
            .map(Throwing.function(inputStream -> OBJECT_MAPPER.readValue(inputStream, ImportRequest.class)))
            .flatMap(importRequest -> handleImport(importRequest, session))
            .then(response.status(202).send());
    }

    private Mono<Void> handleImport(ImportRequest request, MailboxSession session) {
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.fileId), "fileId must be present");
        Preconditions.checkArgument(StringUtils.isNotEmpty(request.target), "target must be present");

        String[] parts = request.target.split("/");
        if (parts.length < 4) {
            throw new IllegalArgumentException("Invalid target path");
        }
        String baseId = parts[2];
        String davCollectionId = parts[3].replace(".json", "");

        return fileDAO.getFile(session.getUser(), new OpenPaaSId(request.fileId))
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Uploaded file not found")))
            .flatMap(uploadedFile -> {
                UploadedMimeType mimeType = uploadedFile.uploadedMimeType();
                return switch (mimeType) {
                    case TEXT_CALENDAR -> {
                        importIcs(uploadedFile, new CalendarURL(new OpenPaaSId(baseId), new OpenPaaSId(davCollectionId)), session.getUser())
                            .then(Mono.fromRunnable(() -> LOGGER.info("ICS with fileId {} are imported successfully", request.fileId)))
                            .doOnError(ex -> LOGGER.error("Error during ICS import with fileId {}", request.fileId, ex))
                            .subscribe();
                        yield Mono.empty();
                    }
                    case TEXT_VCARD -> {
                        importVcards(uploadedFile, new OpenPaaSId(baseId), davCollectionId, session.getUser())
                            .then(Mono.fromRunnable(() -> LOGGER.info("VCARDs with fileId {} are imported successfully", request.fileId)))
                            .doOnError(ex -> LOGGER.error("Error during VCARDs import with fileId {}", request.fileId, ex))
                            .subscribe();
                        yield Mono.empty();
                    }
                    default -> Mono.error(new IllegalArgumentException("Unsupported mime type"));
                };
            });
    }

    private Mono<Void> importIcs(UploadedFile uploadedFile, CalendarURL calendarURL, Username username) {
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
                    return calDavClient.importCalendar(calendarURL, eventId, username, bytes);
                }), DEFAULT_CONCURRENCY))
            .then();
    }

    private Mono<Void> importVcards(UploadedFile uploadedFile, OpenPaaSId userId, String addressBook, Username username) {
        return Mono.fromCallable(() -> Ezvcard.parse(new String(uploadedFile.data(), StandardCharsets.UTF_8)).all())
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)
            .flatMap(vcard -> {
                String vcardUid = UUID.randomUUID().toString();
                vcard.setUid(new Uid(vcardUid));
                String vcardString = Ezvcard.write(vcard)
                    .prodId(false)
                    .go();
                return cardDavClient.createContact(username, userId, addressBook, vcardUid, vcardString.getBytes(StandardCharsets.UTF_8));
            }, DEFAULT_CONCURRENCY)
            .then();
    }
}

