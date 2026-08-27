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


package com.linagora.calendar.smtp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import org.apache.james.core.MailAddress;
import org.apache.james.core.MaybeSender;
import org.apache.james.mime4j.dom.Message;
import org.apache.james.mime4j.message.DefaultMessageBuilder;
import org.apache.james.mime4j.message.DefaultMessageWriter;
import org.apache.james.mime4j.stream.MimeConfig;

import com.linagora.calendar.storage.unsent.UnsentMailRepository.UnsentMail;

public class MimeMessageSerializer {

    public static byte[] asBytes(Message message) throws IOException {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            new DefaultMessageWriter().writeMessage(message, outputStream);
            return outputStream.toByteArray();
        }
    }

    public static Mail toMail(UnsentMail unsentMail) throws IOException {
        DefaultMessageBuilder messageBuilder = new DefaultMessageBuilder();
        messageBuilder.setMimeEntityConfig(MimeConfig.PERMISSIVE);
        Message message = messageBuilder.parseMessage(new ByteArrayInputStream(unsentMail.mimeMessage()));

        return new Mail(unsentMail.mailFrom()
            .map(MaybeSender::of)
            .orElse(MaybeSender.nullSender()),
            List.copyOf(unsentMail.rcptTo()),
            message);
    }

    public static List<MailAddress> recipients(Mail mail) {
        return List.copyOf(mail.recipients());
    }
}
