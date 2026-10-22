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

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;

/**
 * Maps LDAP attributes to the extended vCard properties of the domain member cards,
 * e.g. {@code ldap.vcard.extra.properties=twakeWorkspaceUrl:X-TWAKE-WORKPLACE-FQDN}.
 */
public record LdapVCardPropertiesConfiguration(ImmutableMap<String, String> vcardPropertyByLdapAttribute) {
    public static final String LDAP_VCARD_EXTRA_PROPERTIES = "ldap.vcard.extra.properties";
    public static final LdapVCardPropertiesConfiguration EMPTY = new LdapVCardPropertiesConfiguration(ImmutableMap.of());

    private static final String EXTENDED_PROPERTY_PREFIX = "X-";
    private static final Splitter ENTRIES_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();
    private static final Splitter MAPPING_SPLITTER = Splitter.on(':').trimResults();

    public static LdapVCardPropertiesConfiguration from(Configuration configuration) {
        ImmutableMap<String, String> mapping = Stream.of(configuration.getStringArray(LDAP_VCARD_EXTRA_PROPERTIES))
            .flatMap(ENTRIES_SPLITTER::splitToStream)
            .map(LdapVCardPropertiesConfiguration::parseEntry)
            .collect(ImmutableMap.toImmutableMap(List::getFirst, List::getLast));
        return new LdapVCardPropertiesConfiguration(mapping);
    }

    private static List<String> parseEntry(String entry) {
        List<String> parts = MAPPING_SPLITTER.splitToList(entry);
        Preconditions.checkArgument(parts.size() == 2 && parts.stream().allMatch(StringUtils::isNotEmpty),
            "Invalid '%s' entry '%s', expecting 'ldapAttribute:X-VCARD-PROPERTY'", LDAP_VCARD_EXTRA_PROPERTIES, entry);
        String vcardProperty = parts.getLast().toUpperCase(Locale.US);
        Preconditions.checkArgument(Strings.CS.startsWith(vcardProperty, EXTENDED_PROPERTY_PREFIX),
            "Invalid '%s' entry '%s', the vCard property must start with '%s'", LDAP_VCARD_EXTRA_PROPERTIES, entry, EXTENDED_PROPERTY_PREFIX);
        return List.of(parts.getFirst(), vcardProperty);
    }
}
