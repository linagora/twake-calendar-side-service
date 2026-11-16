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

package com.linagora.calendar.dav;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.util.DurationParser;

import com.google.common.base.Preconditions;

import reactor.netty.resources.ConnectionProvider;

/**
 * Bounds the in flight calls toward the DAV server so that a stalled DAV server is answered with an error
 * in milliseconds once the limit is reached, instead of piling up requests waiting for the response timeout.
 */
public record DavConnectionPoolConfiguration(int maxConnections,
                                             Optional<Integer> pendingAcquireMaxCount,
                                             Optional<Duration> pendingAcquireTimeout) {
    static final String MAX_CONNECTIONS_PROPERTY = "dav.rest.client.max.connections";
    static final String PENDING_ACQUIRE_MAX_COUNT_PROPERTY = "dav.rest.client.pending.acquire.max.count";
    static final String PENDING_ACQUIRE_TIMEOUT_PROPERTY = "dav.rest.client.pending.acquire.timeout";
    private static final String CONNECTION_PROVIDER_NAME = "dav-client";
    private static final int UNBOUNDED_PENDING_ACQUIRE = -1;

    public static Optional<DavConnectionPoolConfiguration> from(Configuration configuration) {
        return Optional.ofNullable(configuration.getInteger(MAX_CONNECTIONS_PROPERTY, null))
            .map(maxConnections -> new DavConnectionPoolConfiguration(maxConnections,
                Optional.ofNullable(configuration.getInteger(PENDING_ACQUIRE_MAX_COUNT_PROPERTY, null)),
                Optional.ofNullable(configuration.getString(PENDING_ACQUIRE_TIMEOUT_PROPERTY, null))
                    .map(string -> DurationParser.parse(string, ChronoUnit.MILLIS))));
    }

    public DavConnectionPoolConfiguration {
        Preconditions.checkArgument(maxConnections > 0, MAX_CONNECTIONS_PROPERTY + " should be positive");
        Preconditions.checkArgument(pendingAcquireMaxCount.map(count -> count >= UNBOUNDED_PENDING_ACQUIRE).orElse(true),
            PENDING_ACQUIRE_MAX_COUNT_PROPERTY + " should be positive, 0 or -1 (unbounded)");
        Preconditions.checkArgument(pendingAcquireTimeout.map(Duration::isPositive).orElse(true),
            PENDING_ACQUIRE_TIMEOUT_PROPERTY + " should be positive");
    }

    public ConnectionProvider asConnectionProvider() {
        ConnectionProvider.Builder builder = ConnectionProvider.builder(CONNECTION_PROVIDER_NAME)
            .maxConnections(maxConnections);
        pendingAcquireMaxCount.ifPresent(builder::pendingAcquireMaxCount);
        pendingAcquireTimeout.ifPresent(builder::pendingAcquireTimeout);
        return builder.build();
    }
}
