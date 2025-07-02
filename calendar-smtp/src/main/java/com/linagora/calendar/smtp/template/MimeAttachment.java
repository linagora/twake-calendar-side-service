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

package com.linagora.calendar.smtp.template;

import java.io.IOException;
import java.util.Optional;

import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.stream.RawField;

public record MimeAttachment(ContentType contentType,
                             Optional<Cid> cid,
                             byte[] content,
                             Optional<String> filename,
                             Optional<String> dispositionType) {
    public static final String INLINE_DISPOSITION_TYPE = "inline";
    public static final String ATTACHMENT_DISPOSITION_TYPE = "attachment";

    public static Builder builder() {
        return new Builder();
    }

    public BodyPart asBodyPart() throws IOException {
        String mediaType = filename
            .map(name -> contentType.asString() + "; name=\"" + name + "\"")
            .orElse(contentType.asString());

        BodyPartBuilder partBuilder = BodyPartBuilder.create()
            .setContentTransferEncoding("base64")
            .setBody(content, mediaType);

        String contentDispositionType = dispositionType
            .orElse(INLINE_DISPOSITION_TYPE);

        filename.ifPresent(name ->
            partBuilder.setContentDisposition(contentDispositionType + "; filename=\"" + name + "\""));

        cid.ifPresent(cidValue ->
            partBuilder.setField(new RawField("Content-ID", cidValue.getValue())));
        return partBuilder.build();
    }


    public static class Builder {
        private ContentType contentType;
        private byte[] content;
        private Optional<Cid> cid = Optional.empty();
        private Optional<String> filename = Optional.empty();
        private Optional<String> dispositionType = Optional.empty();

        public Builder contentType(ContentType contentType) {
            this.contentType = contentType;
            return this;
        }

        public Builder cid(Cid cid) {
            this.cid = Optional.ofNullable(cid);
            return this;
        }

        public Builder content(byte[] content) {
            this.content = content;
            return this;
        }

        public Builder dispositionType(String dispositionType) {
            this.dispositionType = Optional.of(dispositionType);
            return this;
        }

        public Builder fileName(String filename) {
            this.filename = Optional.ofNullable(filename);
            return this;
        }

        public MimeAttachment build() {
            return new MimeAttachment(contentType, cid, content, filename, dispositionType);
        }
    }
}
