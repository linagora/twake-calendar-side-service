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

package com.linagora.calendar.storage.mongodb;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.james.metrics.api.Metric;
import org.apache.james.metrics.api.MetricFactory;
import org.apache.james.metrics.api.TimeMetric;

import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;

public class MongoCommandMetricsListener implements CommandListener {

    public static final String METRIC_PREFIX = "mongodb.command.";

    private final Map<String, Metric> metricMap;
    private final Map<String, TimeMetric> timeMetricMap;
    private final Metric metricOther;
    private final TimeMetric timeMetricOther;

    public MongoCommandMetricsListener(MetricFactory metricFactory) {
        metricMap = Map.of(
            "delete", metricFactory.generate(METRIC_PREFIX + "delete.count"),
            "find", metricFactory.generate(METRIC_PREFIX + "find.count"),
            "findAndModify", metricFactory.generate(METRIC_PREFIX + "findAndModify.count"),
            "insert", metricFactory.generate(METRIC_PREFIX + "insert.count"),
            "update", metricFactory.generate(METRIC_PREFIX + "update.count"));

        timeMetricMap = Map.of(
            "delete", metricFactory.timer(METRIC_PREFIX + "delete.timer"),
            "find", metricFactory.timer(METRIC_PREFIX + "find.timer"),
            "findAndModify", metricFactory.timer(METRIC_PREFIX + "findAndModify.timer"),
            "insert", metricFactory.timer(METRIC_PREFIX + "insert.timer"),
            "update", metricFactory.timer(METRIC_PREFIX + "update.timer"));

        this.metricOther = metricFactory.generate(METRIC_PREFIX + "other.count");
        this.timeMetricOther = metricFactory.timer(METRIC_PREFIX + "other.timer");
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        metricMap.getOrDefault(event.getCommandName(), metricOther).increment();
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        timeMetricMap.getOrDefault(event.getCommandName(), timeMetricOther).record(getElapsedTime(event));
    }

    private Duration getElapsedTime(CommandSucceededEvent succeededEvent) {
        return Duration.ofNanos(succeededEvent.getElapsedTime(TimeUnit.NANOSECONDS));
    }
}