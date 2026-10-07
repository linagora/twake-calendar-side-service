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

package com.linagora.calendar.twakespace;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

public record TwakeSpaceConfiguration(String spaceExchange, String activityExchange) {
    public static final String ENABLED_PROPERTY = "twakespace.enabled";
    public static final String SPACE_EXCHANGE_PROPERTY = "twakespace.exchange";
    public static final String ACTIVITY_EXCHANGE_PROPERTY = "twakespace.activity.exchange";
    public static final String DEFAULT_SPACE_EXCHANGE = "space";
    public static final String DEFAULT_ACTIVITY_EXCHANGE = "activity";

    public static TwakeSpaceConfiguration from(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return from(propertiesProvider.getConfiguration("rabbitmq"));
    }

    public static TwakeSpaceConfiguration from(Configuration configuration) {
        return new TwakeSpaceConfiguration(configuration.getString(SPACE_EXCHANGE_PROPERTY, DEFAULT_SPACE_EXCHANGE),
            configuration.getString(ACTIVITY_EXCHANGE_PROPERTY, DEFAULT_ACTIVITY_EXCHANGE));
    }
}
