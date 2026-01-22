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
public record DomainMembersSyncTaskAdditionalInformationDTO(@JsonProperty("type") String type,
                                                            @JsonProperty("domain") Optional<String> domain,
                                                            @JsonProperty("ignoredDomains") Optional<Set<String>> ignoredDomains,
                                                            @JsonProperty("timestamp") Instant timestamp,
                                                            @JsonProperty("addedCount") int addedCount,
                                                            @JsonProperty("addFailureContacts") ImmutableList<String> addFailureContacts,
                                                            @JsonProperty("updatedCount") int updatedCount,
                                                            @JsonProperty("updateFailureContacts") ImmutableList<String> updateFailureContacts,
                                                            @JsonProperty("deletedCount") int deletedCount,
                                                            @JsonProperty("deleteFailureContacts") ImmutableList<String> deleteFailureContacts) implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<LdapToDavDomainMembersSyncTask.Details, DomainMembersSyncTaskAdditionalInformationDTO> module() {
        return AdditionalInformationDTOModule.forDomainObject(LdapToDavDomainMembersSyncTask.Details.class)
            .convertToDTO(DomainMembersSyncTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(DomainMembersSyncTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(DomainMembersSyncTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(LdapToDavDomainMembersSyncTask.TASK_TYPE.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static DomainMembersSyncTaskAdditionalInformationDTO fromDomainObject(LdapToDavDomainMembersSyncTask.Details details, String type) {
        return new DomainMembersSyncTaskAdditionalInformationDTO(
            type,
            details.domain(),
            details.ignoredDomains(),
            details.timestamp(),
            details.addedCount(),
            details.addFailureContacts(),
            details.updatedCount(),
            details.updateFailureContacts(),
            details.deletedCount(),
            details.deleteFailureContacts());
    }

    private LdapToDavDomainMembersSyncTask.Details toDomainObject() {
        return new LdapToDavDomainMembersSyncTask.Details(
            timestamp,
            domain,
            ignoredDomains,
            addedCount,
            addFailureContacts,
            updatedCount,
            updateFailureContacts,
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
