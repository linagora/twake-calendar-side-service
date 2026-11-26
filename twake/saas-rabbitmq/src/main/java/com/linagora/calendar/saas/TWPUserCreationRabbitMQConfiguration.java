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

package com.linagora.calendar.saas;

import org.apache.commons.configuration2.Configuration;

public record TWPUserCreationRabbitMQConfiguration(String exchange, String routingKey) {
    private static final String TWP_USER_CREATED_EXCHANGE_PROPERTY = "twp.user.created.exchange";
    private static final String TWP_USER_CREATED_ROUTING_KEY_PROPERTY = "twp.user.created.routingKey";
    public static final String TWP_USER_CREATED_EXCHANGE_DEFAULT = "auth";
    public static final String TWP_USER_CREATED_ROUTING_KEY_DEFAULT = "user.created";

    public static TWPUserCreationRabbitMQConfiguration from(Configuration rabbitMQConfiguration) {
        return new TWPUserCreationRabbitMQConfiguration(
            rabbitMQConfiguration.getString(TWP_USER_CREATED_EXCHANGE_PROPERTY, TWP_USER_CREATED_EXCHANGE_DEFAULT),
            rabbitMQConfiguration.getString(TWP_USER_CREATED_ROUTING_KEY_PROPERTY, TWP_USER_CREATED_ROUTING_KEY_DEFAULT));
    }
}
