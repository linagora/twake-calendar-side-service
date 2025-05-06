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

import org.apache.james.core.Username;

import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface UploadedFileDAO {
    Mono<UploadedFile> getFile(OpenPaaSId id);

    Mono<OpenPaaSId> saveFile(Upload upload);

    Mono<Void> deleteFile(OpenPaaSId id);

    Flux<UploadedFile> listFiles(Username username);
}
