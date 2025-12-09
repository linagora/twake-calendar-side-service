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

    private record PurgeContext(long totalCount,
                                long totalBatches,
                                AtomicInteger processedBatchCount,
                                AtomicInteger deletedCount) {

        public static PurgeContext init(long totalCount, int batchSize) {
            long totalBatches = (totalCount + batchSize - 1) / batchSize;
            return new PurgeContext(totalCount, totalBatches,
                new AtomicInteger(0), new AtomicInteger(0));
        }

        int incrementBatch() {
            return processedBatchCount.incrementAndGet();
        }

        void addDeleted(int size) {
            deletedCount.addAndGet(size);
        }

        long remaining() {
            return totalCount - deletedCountValue();
        }

        long progressPercent() {
            return processedBatchCount.get() * 100L / totalBatches;
        }

        int deletedCountValue() {
            return deletedCount.get();
        }
    }

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

        return Mono.zip(dao.countAll(), dao.countOlderThan(threshold))
            .flatMap(tuple -> {
                long totalRecords = tuple.getT1();
                long totalOldRecords = tuple.getT2();
                return proceedWithPurge(threshold, totalRecords, totalOldRecords);
            });
    }

    private Mono<Void> proceedWithPurge(Instant threshold, long totalRecords, long totalOldRecords) {
        out.printf("Found %d total records, %d old records to delete%n", totalRecords, totalOldRecords);
        if (totalOldRecords == 0) {
            out.printf("Purge completed successfully: deleted 0 items, skipped %d recent docs%n", totalRecords);
            return Mono.empty();
        }
        return performBatchDelete(threshold, totalRecords, totalOldRecords);
    }

    private Mono<Void> performBatchDelete(Instant threshold, long totalRecords, long oldRecordsCount) {
        PurgeContext context = PurgeContext.init(oldRecordsCount, batchSize);
        return dao.findOlderThan(threshold)
            .buffer(batchSize)
            .concatMap(batchIds -> deleteBatchWithProgress(batchIds, context))
            .then(Mono.fromRunnable(() -> out.printf("Purge completed successfully: deleted %d items, skipped %d recent docs%n",
                context.deletedCountValue(), totalRecords - context.deletedCountValue())));
    }

    private Mono<Void> deleteBatchWithProgress(List<ObjectId> batchIds, PurgeContext context) {
        out.printf("Batch %d/%d deleted (%d%%) - %d items%n", context.incrementBatch(), context.totalBatches(), context.progressPercent(), batchIds.size());
        return dao.deleteByIds(batchIds)
            .doOnSuccess(v -> context.addDeleted(batchIds.size()))
            .onErrorResume(e -> {
                err.printf("Error during deletion: %s%n", e);
                return Mono.error(e);
            });
    }
}