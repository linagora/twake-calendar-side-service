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

public record AddMissingFieldsTaskAdditionalInformationDTO(String type,
                                                            Instant timestamp,
                                                            long processedUsers,
                                                            long upgradedUsers,
                                                            long errorCount) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<AddMissingFieldsTask.Details, AddMissingFieldsTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(AddMissingFieldsTask.Details.class)
            .convertToDTO(AddMissingFieldsTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(AddMissingFieldsTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(AddMissingFieldsTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(AddMissingFieldsTask.ADD_MISSING_FIELDS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static AddMissingFieldsTaskAdditionalInformationDTO fromDomainObject(AddMissingFieldsTask.Details details, String type) {
        return new AddMissingFieldsTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.processedUsers(),
            details.upgradedUsers(),
            details.errorCount());
    }

    private AddMissingFieldsTask.Details toDomainObject() {
        return new AddMissingFieldsTask.Details(
            timestamp,
            processedUsers,
            upgradedUsers,
            errorCount);
    }
}
