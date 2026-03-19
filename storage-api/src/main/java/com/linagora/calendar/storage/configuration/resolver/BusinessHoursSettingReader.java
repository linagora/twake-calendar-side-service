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

package com.linagora.calendar.storage.configuration.resolver;

import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

public class BusinessHoursSettingReader implements SettingsBasedResolver.SettingReader<List<BusinessHoursSettingReader.BusinessHoursDto>> {

    public static final ObjectMapper OBJECT_MAPPER_DEFAULT = new ObjectMapper()
        .registerModule(new Jdk8Module());

    public record BusinessHoursDto(@JsonProperty("start") String start,
                                   @JsonProperty("end") String end,
                                   @JsonProperty("daysOfWeek") List<Integer> daysOfWeek) {
    }

    public static final EntryIdentifier BUSINESS_HOURS_IDENTIFIER = new EntryIdentifier(
        new ModuleName("core"), new ConfigurationKey("businessHours"));

    private static final TypeReference<List<BusinessHoursDto>> TYPE_REFERENCE = new TypeReference<>() {};

    @Override
    public EntryIdentifier identifier() {
        return BUSINESS_HOURS_IDENTIFIER;
    }

    @Override
    public Optional<List<BusinessHoursDto>> parse(JsonNode jsonNode) {
        return Optional.ofNullable(jsonNode)
            .filter(node -> !node.isNull())
            .map(node -> OBJECT_MAPPER_DEFAULT.convertValue(node, TYPE_REFERENCE));
    }
}
