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
import java.util.Arrays;
import java.util.Objects;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.OpenPaaSId;

public record UploadedFile(OpenPaaSId id, Username username, String fileName, UploadableMimeType uploadableMimeType, Instant created, Long size, byte[] data) {
    public static UploadedFile fromUpload(Username username, OpenPaaSId id, Upload upload) {
        return new UploadedFile(
            id,
            username,
            upload.fileName(),
            upload.uploadableMimeType(),
            upload.created(),
            upload.size(),
            upload.data());
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof UploadedFile other) {
            return Objects.equals(this.size, other.size)
                && Arrays.equals(this.data, other.data)
                && Objects.equals(this.id, other.id)
                && Objects.equals(this.fileName, other.fileName)
                && Objects.equals(this.created, other.created)
                && Objects.equals(this.username, other.username)
                && Objects.equals(this.uploadableMimeType, other.uploadableMimeType);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, username, fileName, uploadableMimeType, created, size, Arrays.hashCode(data));
    }
}
