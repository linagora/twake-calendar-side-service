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

package com.linagora.calendar.scheduling;

import java.io.Closeable;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.lifecycle.api.Startable;
import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.util.DurationParser;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.name.Named;
import com.linagora.calendar.scheduling.AlarmEventSchedulerConfiguration.Mode;
import com.linagora.calendar.storage.AlarmEvent;
import com.linagora.calendar.storage.AlarmEventDAO;
import com.linagora.calendar.storage.AlarmEventLeaseProvider;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class AlarmEventScheduler implements Startable, Closeable {
    private static final Duration LEASE_TTL = DurationParser.parse(System.getProperty("alarm.event.scheduler.lease.ttl", "60s"));

    private static final Logger LOGGER = LoggerFactory.getLogger(AlarmEventScheduler.class);

    private final Clock clock;
    private final AlarmEventDAO alarmEventDAO;
    private final AlarmEventLeaseProvider alarmEventLeaseProvider;
    private final AlarmTriggerService alarmTriggerService;
    private final AlarmEventSchedulerConfiguration configuration;
    private final Metric alarmMetric;
    private final MetricFactory metricFactory;

    private Disposable loop;

    @Inject
    @Singleton
    public AlarmEventScheduler(Clock clock, AlarmEventDAO alarmEventDAO,
                               @Named("scheduler") AlarmEventLeaseProvider alarmEventLeaseProvider,
                               AlarmTriggerService alarmTriggerService,
                               AlarmEventSchedulerConfiguration configuration,
                               MetricFactory metricFactory) {
        this.clock = clock;
        this.alarmEventDAO = alarmEventDAO;
        this.alarmTriggerService = alarmTriggerService;
        this.configuration = configuration;
        this.alarmEventLeaseProvider = alarmEventLeaseProvider;

        this.metricFactory = metricFactory;
        alarmMetric = metricFactory.generate("calendar.alarm");
    }

    public void start() {
        if (Mode.DISABLED.equals(configuration.mode())) {
            LOGGER.info("Alarm event scheduler is disabled");
            return;
        }

        LOGGER.info("Starting AlarmEventScheduler: initialDelay={}, pollInterval={}, batchSize={}",
            configuration.initialJitterMax(), configuration.pollInterval(), configuration.batchSize());

        loop = Flux.interval(configuration.initialJitterMax(), configuration.pollInterval())
            .onBackpressureDrop()
            .concatMap(tick -> pollAndProcess()
                .then(Mono.delay(Duration.ofMillis(ThreadLocalRandom.current().nextLong(0,
                    configuration.initialJitterMax().toMillis()))))
                .onErrorResume(ex -> {
                    LOGGER.warn("pollAndProcess failed", ex);
                    return Mono.empty();
                }))
            .doFinally(signal -> LOGGER.info("AlarmEventScheduler terminating, signal={}", signal))
            .subscribeOn(Schedulers.parallel())
            .subscribe(count -> {
                if (count > 0) {
                    LOGGER.debug("Processed {} alarm(s) this tick", count);
                }
            }, ex -> LOGGER.error("AlarmDeliveryWorker encountered an error", ex));
    }

    @PreDestroy
    @Override
    public void close() {
        if (loop != null && !loop.isDisposed()) {
            loop.dispose();
        }
    }

    private Mono<Long> pollAndProcess() {
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("calendar.alarm.tick.duration",
            alarmEventDAO.findAlarmsToTrigger(clock.instant())
                .take(configuration.batchSize())
                .flatMap(this::processOneAlarm, ReactorUtils.LOW_CONCURRENCY)
                .onErrorResume(ex -> {
                    LOGGER.warn("Batch processing error", ex);
                    return Mono.empty();
                })
                .count()));
    }

    private Mono<Void> processOneAlarm(AlarmEvent alarmEvent) {
        return Mono.from(metricFactory.decoratePublisherWithTimerMetric("calendar.alarm.duration",
            alarmEventLeaseProvider.acquire(alarmEvent, LEASE_TTL)
            .then(alarmTriggerService.sendAlarmAndCleanup(alarmEvent)
                .doOnSuccess(any -> alarmMetric.increment())
                .onErrorResume(error -> {
                    LOGGER.error("Error processing send mail and cleanup for event: {}", alarmEvent.toShortString(), error);
                   return alarmEventLeaseProvider.release(alarmEvent);
                }))
            .onErrorResume(AlarmEventLeaseProvider.LockAlreadyExistsException.class, e -> {
                LOGGER.info("Skipped sending alarm email because another scheduler already acquired the lock for {}",
                    alarmEvent.toShortString());
                return Mono.empty();
            })
            .onErrorResume(ex -> {
                LOGGER.error("Send failed for {}", alarmEvent.toShortString(), ex);
                return Mono.empty();
            })));
    }
}
