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

public record DomainAddressBookImportTaskAdditionalInformationDTO(String type,
                                                                  Instant timestamp,
                                                                  String domain,
                                                                  String addressBookId,
                                                                  long totalContactCount,
                                                                  long importedContactCount,
                                                                  long failedContactCount) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<DomainAddressBookImportTask.Details, DomainAddressBookImportTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(DomainAddressBookImportTask.Details.class)
            .convertToDTO(DomainAddressBookImportTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainAddressBookImportTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainAddressBookImportTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(DomainAddressBookImportTask.IMPORT_DOMAIN_ADDRESS_BOOK.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainAddressBookImportTaskAdditionalInformationDTO fromDomainObject(DomainAddressBookImportTask.Details details, String type) {
        return new DomainAddressBookImportTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.domain(),
            details.addressBookId(),
            details.totalContactCount(),
            details.importedContactCount(),
            details.failedContactCount());
    }

    private DomainAddressBookImportTask.Details toDomainObject() {
        return new DomainAddressBookImportTask.Details(timestamp, domain, addressBookId, totalContactCount, importedContactCount, failedContactCount);
    }
}
