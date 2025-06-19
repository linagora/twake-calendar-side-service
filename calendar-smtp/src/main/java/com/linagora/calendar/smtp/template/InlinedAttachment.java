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

import org.apache.james.mailbox.model.Cid;
import org.apache.james.mailbox.model.ContentType;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.james.mime4j.message.BodyPartBuilder;
import org.apache.james.mime4j.stream.RawField;

public record InlinedAttachment(ContentType contentType, Cid cid, byte[] content, String filename) {
    public BodyPart asBodyPart() throws IOException {
        return BodyPartBuilder.create()
            .setContentDisposition("inline; filename=\"" + filename() +"\"")
            .setField(new RawField("Content-ID", cid().getValue()))
            .setContentTransferEncoding("base64")
            .setBody(content(), contentType().asString() + "; name=\"" + filename() + "\"")
            .build();
    }
}
