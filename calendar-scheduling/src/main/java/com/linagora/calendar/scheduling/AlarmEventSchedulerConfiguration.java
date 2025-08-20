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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.util.DurationParser;

import com.google.common.base.Preconditions;

public record AlarmEventSchedulerConfiguration(Duration pollInterval,
                                               int batchSize,
                                               Duration initialJitterMax,
                                               Mode mode) {
    public enum Mode {
        SINGLE, CLUSTER, DISABLED
    }

    public static final String POLL_INTERVAL_PROPERTY = "alarm.event.scheduler.poll.interval";
    public static final Duration POLL_INTERVAL_DEFAULT = Duration.ofSeconds(60);

    public static final String BATCH_SIZE_PROPERTY = "alarm.event.scheduler.batch.size";
    public static final int BATCH_SIZE_DEFAULT = 1000;

    public static final String INITIAL_JITTER_MAX_PROPERTY = "alarm.event.scheduler.initial.jitter.max";
    public static final Duration INITIAL_JITTER_MAX_DEFAULT = Duration.ofSeconds(30);

    public static final String MODE_PROPERTY = "alarm.event.scheduler.mode";
    public static final Mode MODE_DEFAULT = Mode.DISABLED;

    public static final AlarmEventSchedulerConfiguration DEFAULT =
        new AlarmEventSchedulerConfiguration(POLL_INTERVAL_DEFAULT,
            BATCH_SIZE_DEFAULT, INITIAL_JITTER_MAX_DEFAULT,
            MODE_DEFAULT);

    public static AlarmEventSchedulerConfiguration from(Configuration configuration) {
        Optional<Duration> pollIntervalConfiguration = Optional.ofNullable(configuration.getString(POLL_INTERVAL_PROPERTY, null))
            .map(string -> DurationParser.parse(string, ChronoUnit.SECONDS))
            .map(duration -> {
                Preconditions.checkArgument(duration.isPositive(), "'%s' must be positive".formatted(POLL_INTERVAL_PROPERTY));
                return duration;
            });

        Optional<Integer> batchSizeConfiguration = Optional.ofNullable(configuration.getString(BATCH_SIZE_PROPERTY, null))
            .map(Integer::parseInt)
            .map(bathSize -> {
                Preconditions.checkArgument(bathSize > 0, "'%s' must be positive".formatted(BATCH_SIZE_PROPERTY));
                return bathSize;
            });

        Optional<Duration> initialJitterMaxConfiguration = Optional.ofNullable(configuration.getString(INITIAL_JITTER_MAX_PROPERTY, null))
            .map(string -> DurationParser.parse(string, ChronoUnit.SECONDS))
            .map(duration -> {
                Preconditions.checkArgument(duration.isPositive(), "'%s' must be positive".formatted(INITIAL_JITTER_MAX_PROPERTY));
                return duration;
            });

        Optional<Mode> modeCfg = Optional.ofNullable(configuration.getString(MODE_PROPERTY, null))
            .map(String::trim)
            .map(String::toLowerCase)
            .map(v -> switch (v) {
                case "single" -> Mode.SINGLE;
                case "cluster", "distributed", "multi" -> Mode.CLUSTER;
                case "disabled" -> Mode.DISABLED;
                default -> throw new IllegalArgumentException("Invalid value for '" + MODE_PROPERTY + "': " + v);
            });

        return new AlarmEventSchedulerConfiguration(
            pollIntervalConfiguration.orElse(POLL_INTERVAL_DEFAULT),
            batchSizeConfiguration.orElse(BATCH_SIZE_DEFAULT),
            initialJitterMaxConfiguration.orElse(INITIAL_JITTER_MAX_DEFAULT),
            modeCfg.orElse(MODE_DEFAULT));
    }

    public AlarmEventSchedulerConfiguration {
        Preconditions.checkArgument(pollInterval != null && pollInterval.isPositive(), "pollInterval must be positive");
        Preconditions.checkArgument(batchSize > 0, "batchSize must be positive");
        Preconditions.checkArgument(initialJitterMax != null && initialJitterMax.isPositive(), "initialJitterMax must be positive");
        Preconditions.checkNotNull(mode, "mode must not be null");
    }

}
