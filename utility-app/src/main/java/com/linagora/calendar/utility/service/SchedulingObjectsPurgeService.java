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

package com.linagora.calendar.utility.service;

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.types.ObjectId;

import com.linagora.calendar.utility.repository.MongoSchedulingObjectsDAO;

import reactor.core.publisher.Mono;

public class SchedulingObjectsPurgeService {

    private final PrintStream out;
    private final PrintStream err;

    private static final int DEFAULT_BATCH_SIZE = 100;

    private final MongoSchedulingObjectsDAO dao;
    private final int batchSize;

    public SchedulingObjectsPurgeService(MongoSchedulingObjectsDAO dao, PrintStream out, PrintStream err) {
        this.dao = dao;
        this.batchSize = DEFAULT_BATCH_SIZE;
        this.out = out;
        this.err = err;
    }

    public Mono<Void> purge(Duration retention) {
        if (retention.isZero() || retention.isNegative()) {
            err.println("Retention must be greater than 0. Example: 30d, 6h, 1y");
            return Mono.error(new IllegalArgumentException("Retention must be > 0"));
        }
        Instant threshold = Instant.now().minus(retention);

        out.printf("Starting purge of schedulingobjects older than %s%n", threshold);

        return dao.countOlderThan(threshold)
            .flatMap(total -> {
                out.printf("Found %d schedulingobjects to delete%n", total);
                if (total == 0) {
                    return Mono.empty();
                }
                return performBatchDelete(threshold, total);
            });
    }

    private Mono<Void> performBatchDelete(Instant threshold, long totalCount) {
        int totalBatches = (int) (totalCount + batchSize - 1) / batchSize;
        AtomicInteger processedBatchCount = new AtomicInteger(0);

        return dao.findOlderThan(threshold)
            .buffer(batchSize)
            .concatMap(batchIds -> deleteBatchWithProgress(batchIds, processedBatchCount, totalBatches))
            .then(Mono.fromRunnable(() -> out.println("Purge completed successfully")));
    }

    private Mono<Void> deleteBatchWithProgress(List<ObjectId> batchIds, AtomicInteger processedBatchCount, int totalBatches) {
        int currentBatch = processedBatchCount.incrementAndGet();
        long progressPercent = (long) currentBatch * 100 / totalBatches;

        out.printf("Batch %d/%d deleted (%d%%) - %d items%n", currentBatch, totalBatches, progressPercent, batchIds.size());
        return dao.deleteByIds(batchIds)
            .onErrorResume(e -> {
                err.printf("Error while deleting batch %d/%d: %s%n", currentBatch, totalBatches, e);
                return Mono.error(e);
            });
    }
}