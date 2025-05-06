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

package com.linagora.calendar.storage.model;

import java.time.Instant;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.OpenPaaSId;

public record UploadedFile(OpenPaaSId id, Username username, String fileName, Instant created, Long size, byte[] data) {
    public static UploadedFile fromUpload(OpenPaaSId id, Upload upload) {
        return new UploadedFile(
            id,
            upload.username(),
            upload.fileName(),
            upload.created(),
            upload.size(),
            upload.data()
        );
    }
}
