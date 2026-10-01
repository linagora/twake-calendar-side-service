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

package com.linagora.calendar.commoncontacts.api;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.utils.InitializationOperation;
import org.apache.james.utils.InitilizationOperationBuilder;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.multibindings.ProvidesIntoSet;

public class CommonContactsApiModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(CommonContactsApiServer.class).in(Scopes.SINGLETON);
        Multibinder.newSetBinder(binder(), GuiceProbe.class).addBinding().to(CommonContactsApiServerProbe.class);
    }

    @Provides
    @Singleton
    CommonContactsApiConfiguration provideConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException, FileNotFoundException {
        return CommonContactsApiConfiguration.parse(propertiesProvider.getConfiguration("configuration"));
    }

    @ProvidesIntoSet
    InitializationOperation startCommonContactsApi(CommonContactsApiServer server) {
        return InitilizationOperationBuilder
            .forClass(CommonContactsApiServer.class)
            .init(server::start);
    }
}
