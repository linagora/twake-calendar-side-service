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

import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

@JsonInclude(JsonInclude.Include.NON_ABSENT)
public record DomainMembersUserSearchModeTaskAdditionalInformationDTO(@JsonProperty("type") String type,
                                                                      @JsonProperty("domain") String domain,
                                                                      @JsonProperty("userSearchMode") Optional<String> userSearchMode,
                                                                      @JsonProperty("timestamp") Instant timestamp,
                                                                      @JsonProperty("populatedCount") int populatedCount,
                                                                      @JsonProperty("populateFailureUsers") ImmutableList<String> populateFailureUsers,
                                                                      @JsonProperty("deletedCount") int deletedCount,
                                                                      @JsonProperty("deleteFailureContacts") ImmutableList<String> deleteFailureContacts) implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<DomainMembersUserSearchModeTask.Details, DomainMembersUserSearchModeTaskAdditionalInformationDTO> module() {
        return AdditionalInformationDTOModule.forDomainObject(DomainMembersUserSearchModeTask.Details.class)
            .convertToDTO(DomainMembersUserSearchModeTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainMembersUserSearchModeTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainMembersUserSearchModeTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(DomainMembersUserSearchModeTask.TASK_TYPE.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainMembersUserSearchModeTaskAdditionalInformationDTO fromDomainObject(DomainMembersUserSearchModeTask.Details details, String type) {
        return new DomainMembersUserSearchModeTaskAdditionalInformationDTO(
            type,
            details.domain(),
            details.userSearchMode(),
            details.timestamp(),
            details.populatedCount(),
            details.populateFailureUsers(),
            details.deletedCount(),
            details.deleteFailureContacts());
    }

    private DomainMembersUserSearchModeTask.Details toDomainObject() {
        return new DomainMembersUserSearchModeTask.Details(
            timestamp,
            domain,
            userSearchMode,
            populatedCount,
            populateFailureUsers,
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
