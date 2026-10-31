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

package com.linagora.calendar.saas.drive.api;

public class AttachmentStorageException extends RuntimeException {
    public enum Reason {
        INVALID_ATTACHMENT, INVALID_RESPONSE, UPSTREAM_FAILURE, TIMEOUT
    }

    private final Reason reason;
    private final int status;

    public AttachmentStorageException(Reason reason, String message) {
        this(reason, -1, message, null);
    }

    public AttachmentStorageException(Reason reason, int status, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.status = status;
    }

    public Reason reason() {
        return reason;
    }

    /** Upstream HTTP status, or -1 when no response was received. */
    public int status() {
        return status;
    }
}
