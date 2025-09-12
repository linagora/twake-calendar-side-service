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

public record LdapUsersImportTaskAdditionalInformationDTO(String type,
                                                         Instant timestamp,
                                                         long processedUserCount,
                                                         long failedUserCount) implements AdditionalInformationDTO {
    @Override
    public String getType() {
        return type;
    }

    @Override
    public Instant getTimestamp() {
        return timestamp;
    }

    public static AdditionalInformationDTOModule<LdapUsersImportTask.Details, LdapUsersImportTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(LdapUsersImportTask.Details.class)
            .convertToDTO(LdapUsersImportTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(LdapUsersImportTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(LdapUsersImportTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(LdapUsersImportTask.IMPORT_LDAP_USERS.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }

    private static LdapUsersImportTaskAdditionalInformationDTO fromDomainObject(LdapUsersImportTask.Details details, String type) {
        return new LdapUsersImportTaskAdditionalInformationDTO(
            type,
            details.instant(),
            details.processedUserCount(),
            details.failedUserCount());
    }

    private LdapUsersImportTask.Details toDomainObject() {
        return new LdapUsersImportTask.Details(
            timestamp,
            processedUserCount,
            failedUserCount);
    }
}
