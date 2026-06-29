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

import java.time.Instant;
import java.util.Optional;

import org.apache.james.core.Username;
import org.apache.james.json.DTOModule;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.linagora.calendar.storage.booking.BookingLinkPublicId;

public record BookingLinkEventDeletionTaskAdditionalInformationDTO(String type,
                                                                   Instant timestamp,
                                                                   String username,
                                                                   String bookingLinkPublicId,
                                                                   Optional<Instant> since,
                                                                   long deletedEventCount,
                                                                   long failedEventCount) implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<BookingLinkEventDeletionTask.Details, BookingLinkEventDeletionTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(BookingLinkEventDeletionTask.Details.class)
            .convertToDTO(BookingLinkEventDeletionTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(BookingLinkEventDeletionTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(BookingLinkEventDeletionTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(BookingLinkEventDeletionTask.BOOKING_LINK_EVENT_DELETION.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    private static BookingLinkEventDeletionTaskAdditionalInformationDTO fromDomainObject(BookingLinkEventDeletionTask.Details details, String type) {
        return new BookingLinkEventDeletionTaskAdditionalInformationDTO(type,
            details.instant(),
            details.username().asString(),
            details.bookingLinkPublicId().value().toString(),
            details.since(),
            details.deletedEventCount(),
            details.failedEventCount());
    }

    private BookingLinkEventDeletionTask.Details toDomainObject() {
        return new BookingLinkEventDeletionTask.Details(timestamp,
            Username.of(username),
            BookingLinkPublicId.from(bookingLinkPublicId),
            since,
            deletedEventCount,
            failedEventCount);
    }
}
