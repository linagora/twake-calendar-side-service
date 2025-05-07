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

import java.time.Duration;
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
        Upload upload = new Upload(FILE_NAME, created, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_1, upload).block();

        UploadedFile actual = testee().getFile(USER_1, id).block();
        assertThat(actual).isEqualTo(UploadedFile.fromUpload(USER_1, id, upload));
    }

    @Test
    default void getFileShouldReturnEmptyWhenFileDoesNotExist() {
        assertThat(testee().getFile(USER_1, new OpenPaaSId("non-existent-id")).blockOptional()).isEmpty();
    }

    @Test
    default void deleteFileShouldWork() {
        Instant created = Instant.now();
        Upload upload = new Upload(FILE_NAME, created, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_1, upload).block();

        testee().deleteFile(USER_1, id).block();

        assertThat(testee().getFile(USER_1, id).blockOptional()).isEmpty();
    }

    @Test
    default void listFilesShouldReturnOnlyFilesOfGivenUser() {
        Instant now = Instant.now();
        Upload upload1 = new Upload("file1", now, (long) DATA.length, DATA);
        Upload upload2 = new Upload("file2", now, (long) DATA_2.length, DATA_2);
        Upload upload3 = new Upload("file3", now, (long) DATA_2.length, DATA_2);

        OpenPaaSId id1 = testee().saveFile(USER_1, upload1).block();
        OpenPaaSId id2 = testee().saveFile(USER_2, upload2).block();
        OpenPaaSId id3 = testee().saveFile(USER_1, upload3).block();

        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();
        assertThat(files).containsExactlyInAnyOrder(
            UploadedFile.fromUpload(USER_1, id1, upload1),
            UploadedFile.fromUpload(USER_1, id3, upload3)
        );
    }

    @Test
    default void listFilesShouldReturnEmptyWhenNoFiles() {
        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();
        assertThat(files).isEmpty();
    }

    @Test
    default void user1CannotReadUploadOfUser2() {
        Instant now = Instant.now();
        Upload upload = new Upload(FILE_NAME, now, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_2, upload).block();

        assertThat(testee().getFile(USER_1, id).blockOptional()).isEmpty();
    }

    @Test
    default void user1ICannotDeleteUploadOfUser2() {
        Instant now = Instant.now();
        Upload upload = new Upload(FILE_NAME, now, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_2, upload).block();

        testee().deleteFile(USER_1, id).block();

        assertThat(testee().getFile(USER_2, id).blockOptional()).isPresent();
    }

    @Test
    default void deleteShouldBeIdempotent() {
        Instant now = Instant.now();
        Upload upload = new Upload(FILE_NAME, now, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_1, upload).block();

        testee().deleteFile(USER_1, id).block();
        testee().deleteFile(USER_1, id).block(); // second delete should not throw

        assertThat(testee().getFile(USER_1, id).blockOptional()).isEmpty();
    }

    @Test
    default void deletedFilesShouldNotBeListed() {
        Instant now = Instant.now();
        Upload upload = new Upload("file1", now, (long) DATA.length, DATA);
        OpenPaaSId id = testee().saveFile(USER_1, upload).block();

        testee().deleteFile(USER_1, id).block();

        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();
        assertThat(files).doesNotContain(UploadedFile.fromUpload(USER_1, id, upload));
    }

    @Test
    default void uploadSameFileTwiceShouldGenerateDifferentIds() {
        Instant now = Instant.now();
        Upload upload = new Upload(FILE_NAME, now, (long) DATA.length, DATA);

        OpenPaaSId id1 = testee().saveFile(USER_1, upload).block();
        OpenPaaSId id2 = testee().saveFile(USER_1, upload).block();

        assertThat(id1).isNotEqualTo(id2);
    }

    @Test
    default void expiredFilesCannotBeGet() {
        Instant instant = Instant.now().minus(FileUploadConfiguration.DEFAULT_EXPIRATION.plus(Duration.ofMinutes(1)));
        Upload upload = new Upload(FILE_NAME, instant, (long) DATA.length, DATA);

        OpenPaaSId id = testee().saveFile(USER_1, upload).block();

        assertThat(testee().getFile(USER_1, id).blockOptional()).isEmpty();
    }

    @Test
    default void expiredFilesCannotBeListed() {
        Instant instant = Instant.now().minus(FileUploadConfiguration.DEFAULT_EXPIRATION.plus(Duration.ofMinutes(1)));
        Upload expiredUpload = new Upload("expired", instant, (long) DATA.length, DATA);
        Upload validUpload = new Upload("valid", Instant.now(), (long) DATA.length, DATA);

        OpenPaaSId expiredId = testee().saveFile(USER_1, expiredUpload).block();
        OpenPaaSId validId = testee().saveFile(USER_1, validUpload).block();

        List<UploadedFile> files = testee().listFiles(USER_1).collectList().block();

        assertThat(files)
            .containsExactly(UploadedFile.fromUpload(USER_1, validId, validUpload));
    }
}
