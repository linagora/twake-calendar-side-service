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


package com.linagora.calendar.storage.unsent;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.core.MailAddress;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Singleton
public class MemoryUnsentMailRepository implements UnsentMailRepository {
    private record Entry(long sequence, UnsentMail unsentMail) {
    }

    private final Map<UnsentMailId, Entry> store = new ConcurrentHashMap<>();
    private final AtomicLong sequenceGenerator = new AtomicLong();
    private final Clock clock;

    @Inject
    public MemoryUnsentMailRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<UnsentMailId> store(Optional<MailAddress> mailFrom, List<MailAddress> rcptTo,
                                    byte[] mimeMessage, SendingTrial firstTrial) {
        return Mono.fromCallable(() -> {
            UnsentMailId id = UnsentMailId.generate();
            store.put(id, new Entry(sequenceGenerator.incrementAndGet(),
                new UnsentMail(id, mailFrom, rcptTo, mimeMessage, clock.instant(), List.of(firstTrial))));
            return id;
        });
    }

    @Override
    public Mono<UnsentMail> read(UnsentMailId id) {
        return Mono.fromCallable(() -> store.get(id))
            .map(Entry::unsentMail);
    }

    @Override
    public Flux<UnsentMailId> list(UnsentMailQuery query) {
        return search(query).map(UnsentMail::id);
    }

    @Override
    public Flux<UnsentMail> search(UnsentMailQuery query) {
        return Flux.defer(() -> Flux.fromStream(matching(query)))
            .take(query.limit().map(Integer::longValue).orElse(Long.MAX_VALUE));
    }

    @Override
    public Mono<Void> appendTrial(UnsentMailId id, SendingTrial trial) {
        return Mono.fromRunnable(() -> store.computeIfPresent(id,
            (key, entry) -> new Entry(entry.sequence(), entry.unsentMail().withTrial(trial))));
    }

    @Override
    public Mono<Void> delete(UnsentMailId id) {
        return Mono.fromRunnable(() -> store.remove(id));
    }

    @Override
    public Mono<Void> deleteAll() {
        return Mono.fromRunnable(store::clear);
    }

    @Override
    public Mono<Long> count(UnsentMailQuery query) {
        return Mono.fromCallable(() -> matching(query)
            .limit(query.limit().map(Integer::longValue).orElse(Long.MAX_VALUE))
            .count());
    }

    private Stream<UnsentMail> matching(UnsentMailQuery query) {
        return store.values().stream()
            .sorted(Comparator.comparingLong(Entry::sequence))
            .map(Entry::unsentMail)
            .filter(query::matches);
    }
}
