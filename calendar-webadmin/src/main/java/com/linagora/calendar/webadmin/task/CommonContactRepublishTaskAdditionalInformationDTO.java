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

public record CommonContactRepublishTaskAdditionalInformationDTO(String type,
                                                                 Instant timestamp,
                                                                 long processedContactCount,
                                                                 long failedContactCount,
                                                                 long failedAddressBookCount,
                                                                 long failedUserCount,
                                                                 long failedDomainCount,
                                                                 Optional<Integer> contactsPerSecond) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<CommonContactRepublishTask.Details, CommonContactRepublishTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(CommonContactRepublishTask.Details.class)
            .convertToDTO(CommonContactRepublishTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(CommonContactRepublishTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(CommonContactRepublishTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(CommonContactRepublishTask.REPUBLISH_COMMON_CONTACTS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static CommonContactRepublishTaskAdditionalInformationDTO fromDomainObject(CommonContactRepublishTask.Details details, String type) {
        return new CommonContactRepublishTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.processedContactCount(),
            details.failedContactCount(),
            details.failedAddressBookCount(),
            details.failedUserCount(),
            details.failedDomainCount(),
            Optional.of(details.contactsPerSecond()));
    }

    private CommonContactRepublishTask.Details toDomainObject() {
        return new CommonContactRepublishTask.Details(
            timestamp,
            processedContactCount,
            failedContactCount,
            failedAddressBookCount,
            failedUserCount,
            failedDomainCount,
            contactsPerSecond.orElse(CommonContactRepublishTask.RunningOptions.DEFAULT_CONTACTS_PER_SECOND));
    }
}
