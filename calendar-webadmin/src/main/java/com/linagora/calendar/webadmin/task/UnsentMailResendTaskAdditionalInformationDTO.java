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

public record UnsentMailResendTaskAdditionalInformationDTO(String type,
                                                           Instant timestamp,
                                                           long sentCount,
                                                           long failedCount,
                                                           Optional<String> unsentMailId,
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

    public static AdditionalInformationDTOModule<UnsentMailResendTask.Details, UnsentMailResendTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(UnsentMailResendTask.Details.class)
            .convertToDTO(UnsentMailResendTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(UnsentMailResendTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(UnsentMailResendTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(UnsentMailResendTask.RESEND_UNSENT_MAILS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static UnsentMailResendTaskAdditionalInformationDTO fromDomainObject(UnsentMailResendTask.Details details, String type) {
        return new UnsentMailResendTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.sentCount(),
            details.failedCount(),
            details.unsentMailId(),
            details.sender(),
            details.recipient(),
            details.limit());
    }

    private UnsentMailResendTask.Details toDomainObject() {
        return new UnsentMailResendTask.Details(timestamp, sentCount, failedCount, unsentMailId, sender, recipient, limit);
    }
}
