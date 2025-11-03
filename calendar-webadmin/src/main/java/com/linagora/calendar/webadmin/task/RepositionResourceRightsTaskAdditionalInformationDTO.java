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

public record RepositionResourceRightsTaskAdditionalInformationDTO(String type,
                                                                   Instant timestamp,
                                                                   long processedResourceCount,
                                                                   long failedResourceCount) implements AdditionalInformationDTO {

    public static AdditionalInformationDTOModule<RepositionResourceRightsTask.Details, RepositionResourceRightsTaskAdditionalInformationDTO> module() {
        return DTOModule.forDomainObject(RepositionResourceRightsTask.Details.class)
            .convertToDTO(RepositionResourceRightsTaskAdditionalInformationDTO.class)
            .toDomainObjectConverter(RepositionResourceRightsTaskAdditionalInformationDTO::toDomainObject)
            .toDTOConverter(RepositionResourceRightsTaskAdditionalInformationDTO::fromDomainObject)
            .typeName(RepositionResourceRightsTask.TYPE.asString())
            .withFactory(AdditionalInformationDTOModule::new);
    }


    private static RepositionResourceRightsTaskAdditionalInformationDTO fromDomainObject(RepositionResourceRightsTask.Details details, String type) {
        return new RepositionResourceRightsTaskAdditionalInformationDTO(type, details.timestamp(),
            details.processedResourceCount(), details.failedResourceCount());
    }

    private RepositionResourceRightsTask.Details toDomainObject() {
        return new RepositionResourceRightsTask.Details(processedResourceCount, failedResourceCount, timestamp);
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