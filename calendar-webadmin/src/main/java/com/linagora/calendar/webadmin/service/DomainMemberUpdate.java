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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.james.core.MailAddress;

import com.google.common.collect.Sets;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.ldap.LdapDomainMember;

public record DomainMemberUpdate(Set<AddressBookContact> added,
                                 Set<AddressBookContact> deleted,
                                 Set<AddressBookContact> updated) {

    public static DomainMemberUpdate compute(Collection<LdapDomainMember> sourceOfTruth,
                                             Collection<AddressBookContact> projectionContent) {

        Map<MailAddress, LdapDomainMember> sourceByMail = sourceOfTruth.stream()
            .filter(m -> m.mail().isPresent())
            .collect(Collectors.toMap(member -> member.mail().get(), Function.identity()));

        Map<MailAddress, AddressBookContact> projectionByMail = projectionContent.stream()
            .filter(c -> c.mail().isPresent())
            .collect(Collectors.toMap(contact -> contact.mail().get(), Function.identity()));

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

    private static boolean isChanged(LdapDomainMember ldapDomainMember, AddressBookContact davContact) {
        AddressBookContact ldapAsContact = toAddressBookContact(ldapDomainMember, davContact);

        return !Objects.equals(ldapAsContact.givenName(), davContact.givenName())
            || !Objects.equals(ldapAsContact.familyName(), davContact.familyName())
            || !Objects.equals(ldapAsContact.mail(), davContact.mail())
            || !Objects.equals(ldapAsContact.telephoneNumber(), davContact.telephoneNumber())
            || (ldapAsContact.displayName().isPresent() && !Objects.equals(ldapAsContact.displayName(), davContact.displayName()));
    }

    private static AddressBookContact toAddressBookContact(LdapDomainMember ldap) {
        return toAddressBookContact(ldap, computeUid(ldap.uid(), ldap.mail()));
    }

    private static AddressBookContact toAddressBookContact(LdapDomainMember ldap, AddressBookContact existing) {
        return toAddressBookContact(ldap, computeUid(existing.uid(), existing.mail()));
    }

    public static AddressBookContact toAddressBookContact(LdapDomainMember ldap,
                                                          Optional<String> uid) {
        return AddressBookContact.builder()
            .uid(uid)
            .familyName(Optional.ofNullable(ldap.sn()))
            .givenName(ldap.givenName())
            .displayName(ldap.displayName())
            .mail(ldap.mail())
            .telephoneNumber(ldap.telephoneNumber())
            .build();
    }
}