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

import org.apache.james.core.Username;

import reactor.core.publisher.Mono;

public interface AttachmentStorageClient {
    /**
     * Stores an attachment for the user identified by username/email and returns a public read-only URI.
     * Work starts on subscription. Invalid input and storage failures signal AttachmentStorageException.
     * Each subscription performs an upload; callers must not retry blindly after a partial failure.
     */
    Mono<AttachmentUri> upload(Attachment attachment, Username username);
}
