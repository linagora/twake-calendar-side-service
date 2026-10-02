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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.linagora.calendar.utility.repository.MongoCardsDAO;
import com.linagora.calendar.utility.repository.MongoCardsDAO.SortName;
import com.mongodb.MongoBulkWriteException;

import reactor.core.publisher.Mono;

/**
 * Stores the sortable full name (fn_sort) of the contacts written before esn-sabre stored it: contact listings
 * aggregated over address books leave them out until then. Idempotent: contacts already having one are skipped,
 * so it can be run again, also while esn-sabre is serving requests.
 */
public class ContactsSortNameMigrationService {

    public static final int DEFAULT_BATCH_SIZE = 100;

    private record MigrationContext(long totalBatches, AtomicInteger processedBatchCount, AtomicLong updatedCount,
                                    AtomicInteger failedBatchCount, AtomicLong failedContactCount) {

        static MigrationContext init(long totalCount, int batchSize) {
            return new MigrationContext((totalCount + batchSize - 1) / batchSize, new AtomicInteger(0), new AtomicLong(0),
                new AtomicInteger(0), new AtomicLong(0));
        }

        long progressPercent(int batch) {
            return Math.min(100, batch * 100L / totalBatches);
        }
    }

    private final MongoCardsDAO dao;
    private final PrintStream out;
    private final PrintStream err;

    public ContactsSortNameMigrationService(MongoCardsDAO dao, PrintStream out, PrintStream err) {
        this.dao = dao;
        this.out = out;
        this.err = err;
    }

    public Mono<Void> migrate(int batchSize) {
        if (batchSize <= 0) {
            return Mono.error(new IllegalArgumentException("Batch size must be greater than 0"));
        }

        return Mono.zip(dao.countAll(), dao.countWithoutSortName())
            .flatMap(counts -> {
                out.printf("Found %d contacts, %d without sortable full name%n", counts.getT1(), counts.getT2());
                if (counts.getT2() == 0) {
                    out.println("Migration completed successfully: updated 0 contacts");
                    return Mono.empty();
                }
                return migrateBatches(counts.getT2(), batchSize);
            });
    }

    private Mono<Void> migrateBatches(long totalCount, int batchSize) {
        MigrationContext context = MigrationContext.init(totalCount, batchSize);

        return dao.findWithoutSortName(batchSize)
            .map(card -> new SortName(card.id(), ContactSortName.fromVCard(card.cardData())))
            .buffer(batchSize)
            .concatMap(sortNames -> migrateBatch(sortNames, context))
            .then(Mono.defer(() -> complete(context)));
    }

    /**
     * A batch failing to be stored is skipped and the next ones are processed: its contacts still lack a sortable
     * full name, so running the command again processes them.
     */
    private Mono<Long> migrateBatch(List<SortName> sortNames, MigrationContext context) {
        int batch = context.processedBatchCount().incrementAndGet();

        return dao.setSortNames(sortNames)
            .doOnNext(updated -> {
                context.updatedCount().addAndGet(updated);
                out.printf("Batch %d/%d (%d%%) - %d contacts updated%n", batch, context.totalBatches(),
                    context.progressPercent(batch), updated);
            })
            // Updates are unordered: only the ones reported in error failed, the others were stored
            .onErrorResume(MongoBulkWriteException.class, e -> {
                long updated = e.getWriteResult().getModifiedCount();
                int failed = e.getWriteErrors().size();
                recordFailure(context, updated, failed);
                err.printf("Batch %d/%d partially failed: %d contacts updated, %d skipped: %s%n", batch, context.totalBatches(),
                    updated, failed, e.getWriteErrors().get(0).getMessage());
                return Mono.just(updated);
            })
            // Whether any update was stored is unknown: the whole batch is reported as skipped
            .onErrorResume(e -> {
                recordFailure(context, 0, sortNames.size());
                err.printf("Batch %d/%d failed, skipping its %d contacts: %s%n", batch, context.totalBatches(), sortNames.size(), e);
                return Mono.just(0L);
            });
    }

    private void recordFailure(MigrationContext context, long updated, int failed) {
        context.updatedCount().addAndGet(updated);
        context.failedBatchCount().incrementAndGet();
        context.failedContactCount().addAndGet(failed);
    }

    private Mono<Void> complete(MigrationContext context) {
        if (context.failedBatchCount().get() == 0) {
            out.printf("Migration completed successfully: updated %d contacts%n", context.updatedCount().get());
            return Mono.empty();
        }

        String message = String.format("Migration completed with errors: updated %d contacts, %d batches (%d contacts) failed. "
            + "Run the command again to process them.", context.updatedCount().get(), context.failedBatchCount().get(),
            context.failedContactCount().get());
        err.println(message);
        return Mono.error(new IllegalStateException(message));
    }
}
