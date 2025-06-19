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

package com.linagora.calendar.smtp.template;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class TemplateModule extends AbstractModule {
    @Provides
    @Singleton
    MailTemplateConfiguration config(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return MailTemplateConfiguration.from(propertiesProvider.getConfiguration("configuration"));
    }

    @Provides
    @Singleton
    MessageGenerator.Factory messageGeneratorFactory(MailTemplateConfiguration configuration, FileSystem fileSystem) {
        return MessageGenerator.factory(configuration, fileSystem).cached();
    }
}
