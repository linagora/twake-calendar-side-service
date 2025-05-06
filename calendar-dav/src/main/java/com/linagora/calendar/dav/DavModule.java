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

package com.linagora.calendar.dav;

import java.io.FileNotFoundException;

import javax.net.ssl.SSLException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;

public class DavModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(DavConfiguration.class).in(Scopes.SINGLETON);
        bind(CalDavClient.class).in(Scopes.SINGLETON);
    }

    @Provides
    public DavConfiguration provideDavConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return DavConfiguration.from(propertiesProvider.getConfiguration("configuration"));
    }

    @Provides
    public CalDavClient provideCalDavClient(DavConfiguration configuration) throws SSLException {
        return new CalDavClient(configuration);
    }
}
