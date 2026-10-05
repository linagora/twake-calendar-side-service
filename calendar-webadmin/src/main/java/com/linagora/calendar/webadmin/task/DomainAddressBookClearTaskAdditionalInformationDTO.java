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

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record DomainAddressBookClearTaskAdditionalInformationDTO(String type,
                                                                 Instant timestamp,
                                                                 String domain,
                                                                 String addressBookId,
                                                                 Optional<String> sourceDomain,
                                                                 long deletedContactCount,
                                                                 long failedContactCount) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<DomainAddressBookClearTask.Details, DomainAddressBookClearTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(DomainAddressBookClearTask.Details.class)
            .convertToDTO(DomainAddressBookClearTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainAddressBookClearTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainAddressBookClearTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(DomainAddressBookClearTask.CLEAR_DOMAIN_ADDRESS_BOOK.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainAddressBookClearTaskAdditionalInformationDTO fromDomainObject(DomainAddressBookClearTask.Details details, String type) {
        return new DomainAddressBookClearTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.domain(),
            details.addressBookId(),
            details.sourceDomain(),
            details.deletedContactCount(),
            details.failedContactCount());
    }

    private DomainAddressBookClearTask.Details toDomainObject() {
        return new DomainAddressBookClearTask.Details(timestamp, domain, addressBookId, sourceDomain, deletedContactCount, failedContactCount);
    }
}
