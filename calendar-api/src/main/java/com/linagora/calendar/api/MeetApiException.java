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

package com.linagora.calendar.api;

/** A conference provider could not create a usable meeting link. */
public class MeetApiException extends RuntimeException {
    private static final int NO_HTTP_STATUS = -1;

    private final int status;

    public MeetApiException(String message) {
        this(message, null);
    }

    public MeetApiException(String message, Throwable cause) {
        super(message, cause);
        this.status = NO_HTTP_STATUS;
    }

    public MeetApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** HTTP status from the provider, or -1 if no status is available. */
    public int status() {
        return status;
    }
}
