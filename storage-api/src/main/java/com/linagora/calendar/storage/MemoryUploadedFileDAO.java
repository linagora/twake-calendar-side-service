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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.james.core.Username;

import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryUploadedFileDAO implements UploadedFileDAO {

    private final Map<OpenPaaSId, UploadedFile> storage = new ConcurrentHashMap<>();

    @Override
    public Mono<UploadedFile> getFile(OpenPaaSId id) {
        return Mono.justOrEmpty(storage.get(id));
    }

    @Override
    public Mono<OpenPaaSId> saveFile(Upload upload) {
        return Mono.fromCallable(() -> {
            OpenPaaSId id = new OpenPaaSId(UUID.randomUUID().toString());
            storage.put(id, UploadedFile.fromUpload(id, upload));
            return id;
        });
    }

    @Override
    public Mono<Void> deleteFile(OpenPaaSId id) {
        return Mono.fromRunnable(() -> storage.remove(id));
    }

    @Override
    public Flux<UploadedFile> listFiles(Username username) {
        return Flux.fromIterable(storage.values())
            .filter(file -> file.username().equals(username));
    }
}

