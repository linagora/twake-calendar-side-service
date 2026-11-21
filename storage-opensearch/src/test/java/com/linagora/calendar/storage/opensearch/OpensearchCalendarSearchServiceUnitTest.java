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

package com.linagora.calendar.storage.opensearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.james.backends.opensearch.ReactorOpenSearchClient;
import org.junit.jupiter.api.Test;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.DeleteByQueryRequest;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.OperationType;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.event.EventFields;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.exception.CalendarSearchIndexingException;

import reactor.core.publisher.Mono;

class OpensearchCalendarSearchServiceUnitTest {
    private static final EventFields MASTER = EventFields.builder()
        .uid("event-uid")
        .calendarURL(CalendarURL.from(new OpenPaaSId("calendar-id")))
        .isRecurrentMaster(true)
        .sequence(3)
        .build();

    private final ReactorOpenSearchClient client = mock(ReactorOpenSearchClient.class);
    private final OpensearchCalendarSearchService testee = new OpensearchCalendarSearchService(client,
        CalendarEventOpensearchConfiguration.DEFAULT);

    @Test
    void indexShouldPropagateBulkItemFailure() throws Exception {
        BulkResponse response = new BulkResponse.Builder()
            .took(0)
            .errors(true)
            .items(new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .index(CalendarEventOpensearchConfiguration.DEFAULT.writeAliasName().getValue())
                .status(409)
                .error(error -> error.type("version_conflict_engine_exception").reason("Update rejected"))
                .build())
            .build();
        when(client.bulk(any(BulkRequest.class))).thenReturn(Mono.just(response));

        assertThatThrownBy(() -> testee.index(CalendarEvents.of(MASTER)).block())
            .isInstanceOf(CalendarSearchIndexingException.class)
            .hasMessageContaining(MASTER.uid().value())
            .satisfies(error -> {
                assertThat(error.getCause()).isInstanceOfSatisfying(OpenSearchException.class, cause -> {
                    assertThat(cause.status()).isEqualTo(409);
                    assertThat(cause.error().reason()).isEqualTo("Update rejected");
                });
            });
    }

    @Test
    void indexShouldNotPruneOccurrencesWhenMasterUpdateIsNoOp() throws Exception {
        BulkResponse response = new BulkResponse.Builder()
            .took(0)
            .errors(false)
            .items(new BulkResponseItem.Builder()
                .operationType(OperationType.Update)
                .index(CalendarEventOpensearchConfiguration.DEFAULT.writeAliasName().getValue())
                .status(200)
                .result("noop")
                .build())
            .build();
        when(client.bulk(any(BulkRequest.class))).thenReturn(Mono.just(response));

        assertThatCode(() -> testee.index(CalendarEvents.of(MASTER)).block())
            .doesNotThrowAnyException();

        verify(client, never()).deleteByQuery(any(DeleteByQueryRequest.class));
    }
}
