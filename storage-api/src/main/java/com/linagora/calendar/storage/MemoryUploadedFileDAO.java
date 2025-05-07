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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.james.core.Username;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MemoryUploadedFileDAO implements UploadedFileDAO {
    private final Table<Username, OpenPaaSId, UploadedFile> table = HashBasedTable.create();
    private final Clock clock;
    private final Duration fileExpiration;

    @Inject
    public MemoryUploadedFileDAO(Clock clock, FileUploadConfiguration configuration) {
        this.clock = clock;
        this.fileExpiration = configuration.fileExpiration();
    }

    @Override
    public Mono<UploadedFile> getFile(Username username, OpenPaaSId id) {
        return Mono.fromCallable(() -> table.get(username, id))
            .filter(uploadedFile -> notExpired(uploadedFile, clock.instant()));
    }

    @Override
    public Mono<OpenPaaSId> saveFile(Username username, Upload upload) {
        return Mono.fromCallable(() -> {
            OpenPaaSId id = new OpenPaaSId(UUID.randomUUID().toString());
            table.put(username, id, UploadedFile.fromUpload(username, id, upload));
            return id;
        });
    }

    @Override
    public Mono<Void> deleteFile(Username username, OpenPaaSId id) {
        return Mono.fromRunnable(() -> table.remove(username, id));
    }

    @Override
    public Flux<UploadedFile> listFiles(Username username) {
        Map<OpenPaaSId, UploadedFile> userFiles = table.row(username);
        Instant now = clock.instant();
        return Flux.fromIterable(userFiles.values())
            .filter(uploadedFile -> notExpired(uploadedFile, now));
    }

    private boolean notExpired(UploadedFile file, Instant now) {
        return file.created().plus(fileExpiration).isAfter(now);
    }
}

