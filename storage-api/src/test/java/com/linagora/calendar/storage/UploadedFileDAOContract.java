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

package com.linagora.calendar.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

public interface UploadedFileDAOContract {

    Username USER_1 = Username.of("user1");
    Username USER_2 = Username.of("user2");
    String FILE_NAME = "file";
    byte[] DATA = "data".getBytes();
    byte[] DATA_2 = "data2".getBytes();

    UploadedFileDAO testee();

    @Test
    default void getFileShouldWork() {
        Instant created = Instant.now();
        Upload upload = new Upload(USER_1, FILE_NAME, created, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(upload).block();

        UploadedFile actual = testee().getFile(id).block();
        assertThat(actual).isEqualTo(UploadedFile.fromUpload(id, upload));
    }

    @Test
    default void getFileShouldReturnEmptyWhenFileDoesNotExist() {
        assertThat(testee().getFile(new OpenPaaSId("non-existent-id")).blockOptional()).isEmpty();
    }

    @Test
    default void deleteFileShouldWork() {
        Instant created = Instant.now();
        Upload upload = new Upload(USER_1, FILE_NAME, created, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(upload).block();

        testee().deleteFile(id).block();

        assertThat(testee().getFile(id).blockOptional()).isEmpty();
    }

    @Test
    default void listFilesShouldReturnOnlyFilesOfGivenUser() {
        Instant now = Instant.now();
        Upload upload1 = new Upload(USER_1, "file1", now, (long) DATA.length, DATA);
        Upload upload2 = new Upload(USER_2, "file2", now, (long) DATA_2.length, DATA_2);
        Upload upload3 = new Upload(USER_1, "file3", now, (long) DATA_2.length, DATA_2);

        OpenPaaSId id1 = testee().saveFile(upload1).block();
        OpenPaaSId id2 = testee().saveFile(upload2).block();
        OpenPaaSId id3 = testee().saveFile(upload3).block();

        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();
        assertThat(files).containsExactlyInAnyOrder(
            UploadedFile.fromUpload(id1, upload1),
            UploadedFile.fromUpload(id3, upload3)
        );
    }

    @Test
    default void listFilesShouldReturnEmptyWhenNoFiles() {
        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();
        assertThat(files).isEmpty();
    }
}
