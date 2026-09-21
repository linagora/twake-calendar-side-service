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

import org.apache.james.json.DTOModule;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

public record CalendarImportTaskAdditionalInformationDTO(String type,
                                                         Instant timestamp,
                                                         String username,
                                                         String calendarId,
                                                         long totalEventCount,
                                                         long importedEventCount,
                                                         long failedEventCount) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<CalendarImportTask.Details, CalendarImportTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(CalendarImportTask.Details.class)
            .convertToDTO(CalendarImportTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(CalendarImportTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(CalendarImportTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(CalendarImportTask.IMPORT_CALENDAR.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static CalendarImportTaskAdditionalInformationDTO fromDomainObject(CalendarImportTask.Details details, String type) {
        return new CalendarImportTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.username(),
            details.calendarId(),
            details.totalEventCount(),
            details.importedEventCount(),
            details.failedEventCount());
    }

    private CalendarImportTask.Details toDomainObject() {
        return new CalendarImportTask.Details(timestamp, username, calendarId, totalEventCount, importedEventCount, failedEventCount);
    }
}
