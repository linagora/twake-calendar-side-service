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

import java.util.Optional;

import org.apache.commons.lang3.BooleanUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.EntryIdentifier;
import com.linagora.calendar.storage.configuration.ModuleName;

public class AlarmSettingReader implements SettingsBasedResolver.SettingReader<Boolean> {
    public static final Boolean ENABLE_ALARM = Boolean.TRUE;

    public static final EntryIdentifier ALARM_SETTING_IDENTIFIER = new EntryIdentifier(new ModuleName("calendar"),
        new ConfigurationKey("alarmEmails"));

    @Override
    public EntryIdentifier identifier() {
        return ALARM_SETTING_IDENTIFIER;
    }

    @Override
    public Optional<Boolean> parse(JsonNode jsonNode) {
        return Optional.ofNullable(jsonNode.asText())
            .map(BooleanUtils::toBoolean);
    }
}
