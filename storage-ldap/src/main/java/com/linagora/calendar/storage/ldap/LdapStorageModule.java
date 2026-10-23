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

package com.linagora.calendar.storage.ldap;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.linagora.calendar.storage.UserContactResolver;
import com.linagora.calendar.storage.UserNameResolver;

public class LdapStorageModule extends AbstractModule {

    @Override
    protected void configure() {
        bind(DefaultLdapDomainMemberProvider.class).in(Scopes.SINGLETON);
        bind(LdapDomainMemberProvider.class).to(DefaultLdapDomainMemberProvider.class);
        bind(LdapUserNameResolver.class).in(Scopes.SINGLETON);
        bind(UserNameResolver.class).to(LdapUserNameResolver.class);
        bind(LdapUserContactResolver.class).in(Scopes.SINGLETON);
        bind(UserContactResolver.class).to(LdapUserContactResolver.class);
    }

    @Provides
    @Singleton
    LdapVCardPropertiesConfiguration provideLdapVCardPropertiesConfiguration(PropertiesProvider propertiesProvider) throws ConfigurationException {
        try {
            return LdapVCardPropertiesConfiguration.from(propertiesProvider.getConfiguration("configuration"));
        } catch (FileNotFoundException e) {
            return LdapVCardPropertiesConfiguration.EMPTY;
        }
    }
}
