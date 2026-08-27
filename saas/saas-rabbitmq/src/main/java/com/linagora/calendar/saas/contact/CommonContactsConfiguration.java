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

package com.linagora.calendar.saas.contact;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

public record CommonContactsConfiguration(String outboundExchange,
                                          String collectedContactsExchange) {
    public static final String DEFAULT_OUTBOUND_EXCHANGE = "twake:contacts:common";
    public static final String DEFAULT_COLLECTED_CONTACTS_EXCHANGE = "twake:contacts:collected";
    public static final String OUTBOUND_EXCHANGE_PROPERTY = "common.contacts.exchange";
    public static final String COLLECTED_CONTACTS_EXCHANGE_PROPERTY = "common.contacts.collected.exchange";

    public static CommonContactsConfiguration from(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        Configuration configuration = propertiesProvider.getConfiguration("rabbitmq");
        return new CommonContactsConfiguration(configuration.getString(OUTBOUND_EXCHANGE_PROPERTY, DEFAULT_OUTBOUND_EXCHANGE),
            configuration.getString(COLLECTED_CONTACTS_EXCHANGE_PROPERTY, DEFAULT_COLLECTED_CONTACTS_EXCHANGE));
    }
}
