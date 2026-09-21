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

public record DomainCalendarImportTaskAdditionalInformationDTO(String type,
                                                               Instant timestamp,
                                                               String domain,
                                                               String calendarType,
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

    public static AdditionalInformationDTOModule<DomainCalendarImportTask.Details, DomainCalendarImportTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(DomainCalendarImportTask.Details.class)
            .convertToDTO(DomainCalendarImportTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainCalendarImportTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainCalendarImportTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(DomainCalendarImportTask.IMPORT_DOMAIN_CALENDAR.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainCalendarImportTaskAdditionalInformationDTO fromDomainObject(DomainCalendarImportTask.Details details, String type) {
        return new DomainCalendarImportTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.domain(),
            details.calendarType(),
            details.calendarId(),
            details.totalEventCount(),
            details.importedEventCount(),
            details.failedEventCount());
    }

    private DomainCalendarImportTask.Details toDomainObject() {
        return new DomainCalendarImportTask.Details(timestamp, domain, calendarType, calendarId,
            totalEventCount, importedEventCount, failedEventCount);
    }
}
