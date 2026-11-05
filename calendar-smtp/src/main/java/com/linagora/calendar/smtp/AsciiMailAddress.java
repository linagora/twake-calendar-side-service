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

import java.net.IDN;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.apache.james.core.MailAddress;

import com.google.common.base.CharMatcher;

/**
 * {@link MailAddress#asString()} gives an internationalized domain in its Unicode form, which neither the SMTP envelope
 * (we do not negotiate SMTPUTF8) nor {@link InternetAddress} (which rejects combining marks, as in {@code उदाहरण.भारत})
 * can carry. Any address leaving {@link MailAddress} for those two goes through this class, so that every mail path
 * agrees on which addresses can be written.
 */
public final class AsciiMailAddress {
    private static final char DOMAIN_SEPARATOR = '@';

    public static String asciiDomain(String address) {
        int separator = address.lastIndexOf(DOMAIN_SEPARATOR);
        if (separator < 0 || CharMatcher.ascii().matchesAllOf(address.substring(separator))) {
            return address;
        }
        try {
            return address.substring(0, separator + 1) + IDN.toASCII(address.substring(separator + 1), IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException e) {
            return address;
        }
    }

    public static InternetAddress toInternetAddress(String address) throws AddressException {
        return new InternetAddress(asciiDomain(address));
    }

    public static InternetAddress toInternetAddress(MailAddress address) throws AddressException {
        return toInternetAddress(address.asString());
    }

    private AsciiMailAddress() {
    }
}
