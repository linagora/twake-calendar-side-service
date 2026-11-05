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

import jakarta.mail.internet.AddressException;

import org.apache.james.core.MailAddress;
import org.apache.james.mime4j.field.address.DefaultAddressParser;
import org.apache.james.mime4j.field.address.ParseException;

import com.linagora.calendar.smtp.AsciiMailAddress;
import com.linagora.calendar.smtp.MailUsernames;

/**
 * An address we send a mail to must also be writable in a To header: mime4j's RFC 5322 parser is stricter than
 * {@link MailAddress} (e.g. it rejects an unquoted backslash, as in {@code a\b@example.com}), and so is jakarta-mail,
 * given the address as the mail is built from it: see {@link AsciiMailAddress}.
 */
public final class HeaderAddressValidator {

    public static boolean isWritableInHeader(String address) {
        try {
            new MailAddress(address);
            DefaultAddressParser.DEFAULT.parseMailbox(address);
            AsciiMailAddress.toInternetAddress(address);
            return true;
        } catch (AddressException | ParseException e) {
            return false;
        }
    }

    /**
     * A recipient of an event mail must be writable in a To header, and be a username as its settings are looked up.
     */
    public static boolean isValidRecipient(String address) {
        try {
            return isWritableInHeader(address) && MailUsernames.asUsername(new MailAddress(address)).isPresent();
        } catch (AddressException e) {
            return false;
        }
    }

    public static boolean isWritableInHeader(MailAddress address) {
        return isWritableInHeader(address.asString());
    }

    private HeaderAddressValidator() {
    }
}
