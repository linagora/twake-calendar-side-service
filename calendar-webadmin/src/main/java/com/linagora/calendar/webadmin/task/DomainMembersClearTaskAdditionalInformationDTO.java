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
import java.util.Set;

import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record DomainMembersClearTaskAdditionalInformationDTO(@JsonProperty("type") String type,
                                                             @JsonProperty("domain") Optional<String> domain,
                                                             @JsonProperty("ignoredDomains") Optional<Set<String>> ignoredDomains,
                                                             @JsonProperty("timestamp") Instant timestamp,
                                                             @JsonProperty("deletedCount") int deletedCount,
                                                             @JsonProperty("deleteFailureContacts") ImmutableList<String> deleteFailureContacts) implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<ClearDavDomainMembersTask.Details, DomainMembersClearTaskAdditionalInformationDTO> module() {
        return AdditionalInformationDTOModule.forDomainObject(ClearDavDomainMembersTask.Details.class)
            .convertToDTO(DomainMembersClearTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainMembersClearTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainMembersClearTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(ClearDavDomainMembersTask.TASK_TYPE.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainMembersClearTaskAdditionalInformationDTO fromDomainObject(ClearDavDomainMembersTask.Details details, String type) {
        return new DomainMembersClearTaskAdditionalInformationDTO(
            type,
            details.domain(),
            details.ignoredDomains(),
            details.timestamp(),
            details.deletedCount(),
            details.deleteFailureContacts());
    }

    private ClearDavDomainMembersTask.Details toDomainObject() {
        return new ClearDavDomainMembersTask.Details(
            timestamp,
            domain,
            ignoredDomains,
            deletedCount,
            deleteFailureContacts);
    }

    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }
}
