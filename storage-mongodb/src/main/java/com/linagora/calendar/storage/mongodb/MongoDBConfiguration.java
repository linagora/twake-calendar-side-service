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

package com.linagora.calendar.storage.mongodb;

import java.util.Optional;

import org.apache.commons.configuration2.AbstractConfiguration;
import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.convert.DisabledListDelimiterHandler;

public record MongoDBConfiguration(String mongoURL, String database) {
    public static MongoDBConfiguration parse(Configuration configuration) {
        if (configuration instanceof AbstractConfiguration) {
            ((AbstractConfiguration) configuration)
                .setListDelimiterHandler(new DisabledListDelimiterHandler());
        }

        return new MongoDBConfiguration(
            Optional.ofNullable(configuration.getString("mongo.url", null))
                .orElseThrow(() -> new IllegalArgumentException("'mongo.url' is mandatory")),
            Optional.ofNullable(configuration.getString("mongo.database", null))
                .orElseThrow(() -> new IllegalArgumentException("'mongo.database' is mandatory")));
    }
}
