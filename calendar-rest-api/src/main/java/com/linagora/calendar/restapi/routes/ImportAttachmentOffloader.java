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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;

import com.google.common.base.CharMatcher;
import com.google.common.base.Preconditions;
import com.linagora.calendar.dav.importer.EventToImport;
import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStore;
import com.linagora.calendar.saas.drive.api.AttachmentUri;

import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.ComponentContainer;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.ParameterList;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.parameter.Encoding;
import net.fortuna.ical4j.model.parameter.FmtType;
import net.fortuna.ical4j.model.parameter.Value;
import net.fortuna.ical4j.model.parameter.XParameter;
import net.fortuna.ical4j.model.property.Attach;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class ImportAttachmentOffloader implements ImportIcsRewriter {
    private static final int BYTES_PER_MIB = 1024 * 1024;

    // Match Sabre's limits for embedded attachments in a single DAV resource.
    private static final int MAX_ATTACHMENTS_PER_RESOURCE = 20;
    private static final int MAX_ATTACHMENT_BYTES = 5 * BYTES_PER_MIB;
    private static final int MAX_TOTAL_ATTACHMENT_BYTES = 10 * BYTES_PER_MIB;
    private static final int MAX_FILENAME_BYTES = 160;

    private static final Pattern MIME_TYPE = Pattern.compile("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+");
    private static final CharMatcher INVALID_FILENAME_CHARS = CharMatcher.javaIsoControl().or(CharMatcher.anyOf("/\\"));
    private static final String X_FILENAME = "X-FILENAME";

    private final AttachmentStore attachmentStore;

    @Inject
    public ImportAttachmentOffloader(AttachmentStore attachmentStore) {
        this.attachmentStore = attachmentStore;
    }

    @Override
    public Mono<byte[]> rewrite(EventToImport event, Username username) {
        return Mono.fromCallable(() -> validatedAttachments(event))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMapMany(Flux::fromIterable)
            .concatMap(attachment -> offloadAttachment(attachment, username))
            .then(Mono.fromSupplier(event::ics));
    }

    private Mono<AttachmentUri> offloadAttachment(EmbeddedAttachment embedded, Username username) {
        // iCal4j can back attachment bytes with a memory-mapped file.
        return Mono.fromCallable(embedded::readAttachment)
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(attachment -> attachmentStore.upload(attachment, username))
            .doOnNext(embedded::replaceWithUri);
    }

    private static List<EmbeddedAttachment> validatedAttachments(EventToImport event) {
        // Validate every attachment before the first upload, using only metadata and byte counts.
        List<EmbeddedAttachment> attachments = event.vEvents().stream()
            .flatMap(ImportAttachmentOffloader::componentTree)
            .flatMap(ImportAttachmentOffloader::embeddedAttachments)
            .limit(MAX_ATTACHMENTS_PER_RESOURCE + 1L)
            .toList();

        long totalBytes = attachments.stream().mapToLong(EmbeddedAttachment::sizeInBytes).sum();
        Preconditions.checkArgument(attachments.size() <= MAX_ATTACHMENTS_PER_RESOURCE
                && totalBytes <= MAX_TOTAL_ATTACHMENT_BYTES,
            "Too many or oversized embedded calendar attachments");
        return attachments;
    }

    private static Stream<Component> componentTree(Component component) {
        if (component instanceof ComponentContainer<?> container) {
            Stream<Component> descendants = container.getComponents().stream()
                .flatMap(ImportAttachmentOffloader::componentTree);
            return Stream.concat(Stream.of(component), descendants);
        }
        return Stream.of(component);
    }

    private static Stream<EmbeddedAttachment> embeddedAttachments(Component component) {
        return component.<Property>getProperties(Property.ATTACH).stream()
            .filter(Attach.class::isInstance)
            .map(Attach.class::cast)
            .filter(ImportAttachmentOffloader::isEmbedded)
            .map(property -> validatedAttachment(component, property));
    }

    private static boolean isEmbedded(Attach property) {
        return property.getBinary() != null
            || property.getParameter(Parameter.VALUE).filter(Value.BINARY::equals).isPresent()
            || property.getParameter(Parameter.ENCODING).filter(Encoding.BASE64::equals).isPresent();
    }

    private static EmbeddedAttachment validatedAttachment(Component component, Attach property) {
        String filename = property.getParameter(X_FILENAME)
            .or(() -> property.getParameter("FILENAME"))
            .map(Parameter::getValue)
            .orElse("attachment.bin");
        String mimeType = property.getParameter(Parameter.FMTTYPE).map(Parameter::getValue)
            .orElse("application/octet-stream");

        Preconditions.checkArgument(!filename.isBlank()
                && filename.getBytes(UTF_8).length <= MAX_FILENAME_BYTES
                && INVALID_FILENAME_CHARS.matchesNoneOf(filename)
                && !filename.equals(".")
                && !filename.equals(".."),
            "Invalid embedded calendar attachment filename");
        Preconditions.checkArgument(MIME_TYPE.matcher(mimeType).matches(), "Invalid embedded calendar attachment MIME type");
        ByteBuffer binary = property.getBinary();
        Preconditions.checkArgument(binary != null && binary.remaining() <= MAX_ATTACHMENT_BYTES,
            "Invalid or oversized embedded calendar attachment");
        return new EmbeddedAttachment(component, property, filename, ContentType.of(mimeType), binary.asReadOnlyBuffer());
    }

    private record EmbeddedAttachment(Component component,
                                      Attach property,
                                      String filename,
                                      ContentType contentType,
                                      ByteBuffer binary) {
        int sizeInBytes() {
            return binary.remaining();
        }

        Attachment readAttachment() {
            byte[] content = new byte[sizeInBytes()];
            binary.duplicate().get(content);
            return new Attachment(filename, contentType, content);
        }

        void replaceWithUri(AttachmentUri uri) {
            ParameterList parameters = new ParameterList(List.of(
                Value.URI,
                new FmtType(contentType.asString()),
                new XParameter(X_FILENAME, filename)));
            Attach replacement = new Attach(parameters, uri.value());
            component.remove(property);
            component.add(replacement);
        }
    }
}
