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
import java.util.List;

import org.apache.james.json.DTOModule;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.linagora.calendar.webadmin.service.CalendarEventsReindexService;

public record CalendarEventsReindexTaskAdditionalInformationDTO(String type,
                                                                Instant timestamp,
                                                                long processedUserCount,
                                                                long processedEventCount,
                                                                long failedEventCount,
                                                                List<String> failedUsers) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<CalendarEventsReindexTask.Details, CalendarEventsReindexTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(CalendarEventsReindexTask.Details.class)
            .convertToDTO(CalendarEventsReindexTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(CalendarEventsReindexTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(CalendarEventsReindexTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(CalendarEventsReindexTask.REINDEX_CALENDAR_EVENTS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static CalendarEventsReindexTaskAdditionalInformationDTO fromDomainObject(CalendarEventsReindexTask.Details details, String type) {
        return new CalendarEventsReindexTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.processedUserCount(),
            details.processedEventCount(),
            details.failedEventCount(),
            details.failedUsers().stream()
                .map(CalendarEventsReindexService.Context.User::username)
                .toList());
    }

    private CalendarEventsReindexTask.Details toDomainObject() {
        return new CalendarEventsReindexTask.Details(
            timestamp,
            processedUserCount,
            processedEventCount,
            failedEventCount,
            failedUsers.stream()
                .map(CalendarEventsReindexService.Context.User::new)
                .toList());
    }
}
