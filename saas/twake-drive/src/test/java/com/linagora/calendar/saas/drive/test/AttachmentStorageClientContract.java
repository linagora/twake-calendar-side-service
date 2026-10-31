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

package com.linagora.calendar.saas.drive.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;

import org.apache.james.core.Username;
import org.apache.james.mailbox.model.ContentType;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.saas.drive.api.Attachment;
import com.linagora.calendar.saas.drive.api.AttachmentStorageClient;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Implement the fixture hooks against a fresh storage backend for each test. */
public interface AttachmentStorageClientContract {
    Username USER = Username.of("alice@example.com");
    byte[] CONTENT = new byte[] {0, 1, 2, 10, 13, 127, (byte) 128, (byte) 255};
    Attachment ATTACHMENT = new Attachment("résumé #1 & notes.pdf", ContentType.of("application/pdf"), CONTENT);

    AttachmentStorageClient client();

    URI expectedUri();

    byte[] storedContent();

    int requestCount();

    void failStorage();

    @Test
    default void shouldReturnPublicUriAndPreserveBinaryContent() {
        StepVerifier.create(client().upload(ATTACHMENT, USER))
            .assertNext(uri -> assertThat(uri.value()).isEqualTo(expectedUri()))
            .expectComplete().verify(Duration.ofSeconds(5));
        assertThat(storedContent()).containsExactly(CONTENT);
    }

    @Test
    default void shouldStartWorkOnlyOnSubscription() {
        Mono<?> upload = client().upload(ATTACHMENT, USER);
        assertThat(upload).isNotNull();
        assertThat(requestCount()).isZero();
    }

    @Test
    default void shouldSupportEmptyFile() {
        client().upload(new Attachment("empty.txt", ContentType.of("text/plain"), new byte[0]), USER).block(Duration.ofSeconds(5));
        assertThat(storedContent()).isEmpty();
    }

    @Test
    default void shouldRejectMissingContentBeforeAnyRequest() {
        assertInvalid(new Attachment("file.txt", ContentType.of("text/plain"), null), USER);
    }

    @Test
    default void shouldRejectPathAsFileName() {
        assertInvalid(new Attachment("../file.txt", ContentType.of("text/plain"), new byte[0]), USER);
    }

    @Test
    default void shouldRejectHeaderInjection() {
        assertInvalid(new Attachment("file.txt", ContentType.of("text/plain\r\nX-Injected: yes"), new byte[0]), USER);
    }

    @Test
    default void shouldRejectMissingContentType() {
        assertInvalid(new Attachment("file.txt", null, new byte[0]), USER);
    }

    @Test
    default void shouldRejectBlankUser() {
        assertInvalid(ATTACHMENT, Username.fromLocalPartWithoutDomain(" "));
    }

    @Test
    default void shouldRejectMissingUserBeforeAnyRequest() {
        assertInvalid(ATTACHMENT, null);
    }

    @Test
    default void shouldRejectMissingAttachment() {
        assertInvalid(null, USER);
    }

    @Test
    default void shouldExposeStorageFailureThroughCommonException() {
        failStorage();
        StepVerifier.create(client().upload(ATTACHMENT, USER))
            .expectError(AttachmentStorageException.class).verify(Duration.ofSeconds(5));
    }

    private void assertInvalid(Attachment attachment, Username user) {
        StepVerifier.create(client().upload(attachment, user))
            .expectErrorSatisfies(error -> assertThat(error).isInstanceOfSatisfying(AttachmentStorageException.class,
                storageError -> assertThat(storageError.reason()).isEqualTo(Reason.INVALID_ATTACHMENT)))
            .verify(Duration.ofSeconds(5));
        assertThat(requestCount()).isZero();
    }
}
