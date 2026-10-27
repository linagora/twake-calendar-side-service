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

import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;

import com.google.common.collect.ImmutableListMultimap;
import com.linagora.calendar.storage.UserContactResolver.UserContact;

/**
 * Builds the contact of an LDAP user, its extra LDAP attributes being written under the vCard properties
 * they are mapped to by {@link LdapVCardPropertiesConfiguration}.
 */
public class LdapUserContactMapper {
    private final LdapVCardPropertiesConfiguration vcardPropertiesConfiguration;

    @Inject
    public LdapUserContactMapper(LdapVCardPropertiesConfiguration vcardPropertiesConfiguration) {
        this.vcardPropertiesConfiguration = vcardPropertiesConfiguration;
    }

    public UserContact toUserContact(LdapUser ldapUser) {
        return UserContact.builder()
            .names(Optional.of(ldapUser.toUserNames()))
            .familyName(ldapUser.sn())
            .givenName(resolveGivenName(ldapUser))
            .displayName(resolveDisplayName(ldapUser))
            .telephoneNumber(ldapUser.telephoneNumber())
            .extraProperties(extraProperties(ldapUser))
            .build();
    }

    // Several LDAP attributes may be mapped to the same vCard property: each present value is kept, in the configuration order.
    private ImmutableListMultimap<String, String> extraProperties(LdapUser ldapUser) {
        return vcardPropertiesConfiguration.vcardPropertyByLdapAttribute().entrySet().stream()
            .flatMap(mapping -> Optional.ofNullable(ldapUser.extraAttributes().get(mapping.getKey()))
                .map(value -> Map.entry(mapping.getValue(), value))
                .stream())
            .collect(ImmutableListMultimap.toImmutableListMultimap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Optional<String> resolveDisplayName(LdapUser ldapUser) {
        return ldapUser.displayName()
            .or(ldapUser::cn)
            .or(() -> ldapUser.mail().map(MailAddress::asString));
    }

    private Optional<String> resolveGivenName(LdapUser ldapUser) {
        return ldapUser.givenName()
            .or(() -> extractGivenNameFromCommonName(ldapUser));
    }

    private Optional<String> extractGivenNameFromCommonName(LdapUser ldapUser) {
        return ldapUser.cn()
            .map(commonName -> Strings.CS.remove(commonName, ldapUser.sn().orElse("")))
            .map(StringUtils::trimToNull);
    }
}
