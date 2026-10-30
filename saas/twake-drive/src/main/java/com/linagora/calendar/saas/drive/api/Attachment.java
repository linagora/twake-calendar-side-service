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

import org.apache.james.mailbox.model.ContentType;

/** Binary content and original metadata. Callers must not mutate content while an upload is in progress. */
public record Attachment(String fileName, ContentType contentType, byte[] content) {
    @Override
    public String toString() {
        return "Attachment[fileName=" + fileName + ", contentType=" + (contentType == null ? null : contentType.asString()) + "]";
    }
}
