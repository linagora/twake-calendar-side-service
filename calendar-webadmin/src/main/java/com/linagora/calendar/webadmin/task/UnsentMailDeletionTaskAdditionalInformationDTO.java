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

import org.apache.james.json.DTOModule;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

public record UnsentMailDeletionTaskAdditionalInformationDTO(String type,
                                                             Instant timestamp,
                                                             long deletedCount,
                                                             long failedCount,
                                                             Optional<String> sender,
                                                             Optional<String> recipient,
                                                             Optional<Integer> limit) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<UnsentMailDeletionTask.Details, UnsentMailDeletionTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(UnsentMailDeletionTask.Details.class)
            .convertToDTO(UnsentMailDeletionTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(UnsentMailDeletionTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(UnsentMailDeletionTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(UnsentMailDeletionTask.DELETE_UNSENT_MAILS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static UnsentMailDeletionTaskAdditionalInformationDTO fromDomainObject(UnsentMailDeletionTask.Details details, String type) {
        return new UnsentMailDeletionTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.deletedCount(),
            details.failedCount(),
            details.sender(),
            details.recipient(),
            details.limit());
    }

    private UnsentMailDeletionTask.Details toDomainObject() {
        return new UnsentMailDeletionTask.Details(timestamp, deletedCount, failedCount, sender, recipient, limit);
    }
}
