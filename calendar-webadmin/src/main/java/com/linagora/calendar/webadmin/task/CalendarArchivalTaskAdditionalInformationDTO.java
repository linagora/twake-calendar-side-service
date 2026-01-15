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

import com.linagora.calendar.webadmin.model.EventArchivalCriteria;

public record CalendarArchivalTaskAdditionalInformationDTO(String type,
                                                           Instant timestamp,
                                                           long archivedEventCount,
                                                           long failedEventCount,
                                                           Optional<String> targetUser,
                                                           CriteriaDetailDTO criteria) implements AdditionalInformationDTO {

    record CriteriaDetailDTO(Optional<Instant> createdBefore,
                             Optional<Instant> lastModifiedBefore,
                             Optional<Instant> masterDtStartBefore,
                             boolean rejectedOnly,
                             boolean isNotRecurring) {
        static CriteriaDetailDTO fromDomainObject(EventArchivalCriteria criteria) {
            return new CriteriaDetailDTO(criteria.createdBefore(),
                criteria.lastModifiedBefore(),
                criteria.masterDtStartBefore(),
                criteria.rejectedOnly(),
                criteria.isNotRecurring());
        }

        public EventArchivalCriteria toDomainObject() {
            return new EventArchivalCriteria(createdBefore, lastModifiedBefore, masterDtStartBefore,
                rejectedOnly, isNotRecurring);
        }
    }

    public static AdditionalInformationDTOModule<CalendarArchivalTask.Details, CalendarArchivalTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(CalendarArchivalTask.Details.class)
            .convertToDTO(CalendarArchivalTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(CalendarArchivalTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(CalendarArchivalTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(CalendarArchivalTask.CALENDAR_ARCHIVAL.asString())
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

    private static CalendarArchivalTaskAdditionalInformationDTO fromDomainObject(CalendarArchivalTask.Details details, String type) {
        return new CalendarArchivalTaskAdditionalInformationDTO(type, details.instant(),
            details.archivedEventCount(), details.failedEventCount(),
            details.targetUser().map(Username::asString),
            CriteriaDetailDTO.fromDomainObject(details.criteria()));
    }

    private CalendarArchivalTask.Details toDomainObject() {
        return new CalendarArchivalTask.Details(timestamp, archivedEventCount, failedEventCount, targetUser.map(Username::of), criteria.toDomainObject());
    }

}