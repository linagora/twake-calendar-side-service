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

package com.linagora.calendar.storage.secretlink;

import java.security.SecureRandom;

import com.google.common.io.BaseEncoding;

public record SecretLinkToken(String value) {

    private static final SecureRandom secureRandom = new SecureRandom();

    public static SecretLinkToken generate() {
        byte[] bytes = new byte[22];
        secureRandom.nextBytes(bytes);
        return new SecretLinkToken(BaseEncoding.base64Url().omitPadding().encode(bytes));
    }

}