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

package com.linagora.calendar.webadmin.service;

import static com.linagora.calendar.dav.AddressBookContact.computeUid;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.ldap.LdapUser;

public record DomainMemberUpdate(Set<AddressBookContact> added,
                                 Set<AddressBookContact> deleted,
                                 Set<AddressBookContact> updated) {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainMemberUpdate.class);

    public static DomainMemberUpdate compute(Collection<LdapUser> sourceOfTruth,
                                             Collection<AddressBookContact> projectionContent) {

        Map<MailAddress, LdapUser> sourceByMail = sourceOfTruth.stream()
            .filter(m -> m.mail().isPresent())
            .collect(Collectors.toMap(member -> member.mail().get(), Function.identity(),
                (first, second) -> {
                    LOGGER.warn("Duplicate mail {} among LDAP domain members (uids {} and {}), keeping the first one",
                        first.mail().get().asString(), first.uid(), second.uid());
                    return first;
                }));

        Map<MailAddress, AddressBookContact> projectionByMail = projectionContent.stream()
            .filter(c -> c.mail().isPresent())
            .collect(Collectors.toMap(contact -> contact.mail().get(), Function.identity(),
                (first, second) -> {
                    LOGGER.warn("Duplicate mail {} among DAV domain members, keeping the first one",
                        first.mail().get().asString());
                    return first;
                }));

        Set<MailAddress> srcKeys = sourceByMail.keySet();
        Set<MailAddress> projKeys = projectionByMail.keySet();

        Set<MailAddress> toAddKeys = Sets.difference(srcKeys, projKeys);
        Set<MailAddress> toDeleteKeys = Sets.difference(projKeys, srcKeys);
        Set<MailAddress> commonKeys = Sets.intersection(sourceByMail.keySet(), projectionByMail.keySet());

        Set<AddressBookContact> added = toAddKeys.stream()
            .map(mail -> toAddressBookContact(sourceByMail.get(mail)))
            .collect(Collectors.toSet());

        Set<AddressBookContact> deleted = toDeleteKeys.stream()
            .map(projectionByMail::get)
            .collect(Collectors.toSet());

        Set<AddressBookContact> updated = commonKeys.stream()
            .filter(mail -> isChanged(sourceByMail.get(mail), projectionByMail.get(mail)))
            .map(mail -> toAddressBookContact(sourceByMail.get(mail), projectionByMail.get(mail)))
            .collect(Collectors.toSet());

        return new DomainMemberUpdate(added, deleted, updated);
    }

    private static boolean isChanged(LdapUser ldapUser, AddressBookContact davContact) {
        return !toAddressBookContact(ldapUser, davContact).hasSameContentAs(davContact);
    }

    private static AddressBookContact toAddressBookContact(LdapUser ldap) {
        return toAddressBookContact(ldap, computeUid(ldap.uid(), ldap.mail()));
    }

    private static AddressBookContact toAddressBookContact(LdapUser ldap, AddressBookContact existing) {
        return toAddressBookContact(ldap, computeUid(existing.uid(), existing.mail()));
    }

    public static AddressBookContact toAddressBookContact(LdapUser ldap,
                                                          Optional<String> uid) {
        return AddressBookContact.builder()
            .uid(uid)
            .mail(ldap.mail())
            .userContact(ldap.toUserContact())
            .build();
    }
}