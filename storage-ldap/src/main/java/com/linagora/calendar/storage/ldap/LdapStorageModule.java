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

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.server.core.configuration.ConfigurationProvider;
import org.apache.james.user.ldap.LDAPConnectionFactory;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;

public class LdapStorageModule extends AbstractModule {

    public static final String LDAP_STORAGE_INJECTION = "LdapStorage";

    @Override
    protected void configure() {
        bind(DefaultLdapDomainMemberProvider.class).in(Scopes.SINGLETON);
        bind(LdapDomainMemberProvider.class).to(DefaultLdapDomainMemberProvider.class);
    }

    @Named(LDAP_STORAGE_INJECTION)
    @Provides
    @Singleton
    public LDAPConnectionPool provideConfiguration(@Named(LDAP_STORAGE_INJECTION) LdapRepositoryConfiguration configuration) throws LDAPException {
        return new LDAPConnectionFactory(configuration).getLdapConnectionPool();
    }

    @Named(LDAP_STORAGE_INJECTION)
    @Provides
    @Singleton
    public LdapRepositoryConfiguration provideConfiguration(ConfigurationProvider configurationProvider) throws ConfigurationException {
        return LdapRepositoryConfiguration.from(
            configurationProvider.getConfiguration("ldapconfig"));
    }
}
