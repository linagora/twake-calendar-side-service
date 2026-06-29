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

package com.linagora.calendar.webadmin.task;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Username;
import org.apache.james.task.Task;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskType;

import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.booking.BookingLinkPublicId;
import com.linagora.calendar.webadmin.service.BookingLinkEventDeletionService;

public class BookingLinkEventDeletionTask implements Task {

    public record Details(Instant instant,
                          Username username,
                          BookingLinkPublicId bookingLinkPublicId,
                          Optional<Instant> since,
                          long deletedEventCount,
                          long failedEventCount) implements TaskExecutionDetails.AdditionalInformation {

        @Override
        public Instant timestamp() {
            return instant;
        }
    }

    public static final TaskType BOOKING_LINK_EVENT_DELETION = TaskType.of("booking-link-event-deletion");

    private final BookingLinkEventDeletionService deletionService;
    private final Username username;
    private final CalendarURL calendarUrl;
    private final BookingLinkPublicId bookingLinkPublicId;
    private final Optional<Instant> since;
    private final BookingLinkEventDeletionService.Context context;

    public BookingLinkEventDeletionTask(BookingLinkEventDeletionService deletionService,
                                        Username username,
                                        CalendarURL calendarUrl,
                                        BookingLinkPublicId bookingLinkPublicId,
                                        Optional<Instant> since) {
        this.deletionService = deletionService;
        this.username = username;
        this.calendarUrl = calendarUrl;
        this.bookingLinkPublicId = bookingLinkPublicId;
        this.since = since;
        this.context = new BookingLinkEventDeletionService.Context();
    }

    @Override
    public Result run() {
        return deletionService.deleteEvents(
                new BookingLinkEventDeletionService.DeletionRequest(username, calendarUrl, bookingLinkPublicId, since), context)
            .block();
    }

    @Override
    public TaskType type() {
        return BOOKING_LINK_EVENT_DELETION;
    }

    @Override
    public Optional<TaskExecutionDetails.AdditionalInformation> details() {
        BookingLinkEventDeletionService.Context.Snapshot snapshot = context.snapshot();
        return Optional.of(new Details(Clock.systemUTC().instant(),
            username,
            bookingLinkPublicId,
            since,
            snapshot.deleted(),
            snapshot.failed()));
    }
}
