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

package com.linagora.calendar.storage;

import java.io.FileNotFoundException;

import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class FileUploadConfigurationModule extends AbstractModule {
    @Provides
    @Singleton
    FileUploadConfiguration fileUploadConfiguration(PropertiesProvider propertiesProvider) throws Exception {
        try {
            return FileUploadConfiguration.parse(propertiesProvider.getConfiguration("configuration"));
        } catch (FileNotFoundException e) {
            return FileUploadConfiguration.DEFAULT;
        }
    }
}
