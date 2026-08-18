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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

public record CommonContactPublisherConfiguration(String exchange) {
    public static final String EXCHANGE_PROPERTY = "common.contacts.exchange";

    public static CommonContactPublisherConfiguration from(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return new CommonContactPublisherConfiguration(propertiesProvider.getConfiguration("rabbitmq")
            .getString(EXCHANGE_PROPERTY, CommonContactPublisher.DEFAULT_EXCHANGE));
    }
}
