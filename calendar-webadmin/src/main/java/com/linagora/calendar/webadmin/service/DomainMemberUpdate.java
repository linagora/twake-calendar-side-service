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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.ldap.LdapUser;

public record DomainMemberUpdate(Set<AddressBookContact> added,
                                 Set<AddressBookContact> deleted,
                                 Set<AddressBookContact> updated) {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainMemberUpdate.class);

    public static DomainMemberUpdate compute(Collection<LdapUser> sourceOfTruth,
                                             Collection<AddressBookContact> projectionContent) {

        // LDAP can expose several accounts sharing the same mail (e.g. a deploy account and its
        // snapshots counterpart). Keep a single deterministic source per mail (lowest uid).
        Map<MailAddress, LdapUser> sourceByMail = sourceOfTruth.stream()
            .filter(m -> m.mail().isPresent())
            .collect(Collectors.toMap(member -> member.mail().get(), Function.identity(),
                (first, second) -> {
                    LOGGER.warn("Duplicate mail {} among LDAP domain members (uids {} and {}), keeping the lowest uid",
                        first.mail().get().asString(), first.uid(), second.uid());
                    return uid(first).compareTo(uid(second)) <= 0 ? first : second;
                }));

        // The DAV projection may already hold several contacts for the same mail (duplicates created
        // before this deduplication existed). Group them so surplus duplicates can be deleted.
        Map<MailAddress, List<AddressBookContact>> projectionByMail = projectionContent.stream()
            .filter(c -> c.mail().isPresent())
            .collect(Collectors.groupingBy(contact -> contact.mail().get()));

        Set<AddressBookContact> added = new HashSet<>();
        Set<AddressBookContact> deleted = new HashSet<>();
        Set<AddressBookContact> updated = new HashSet<>();

        // Mails present in DAV but no longer in LDAP: delete every matching contact.
        projectionByMail.forEach((mail, contacts) -> {
            if (!sourceByMail.containsKey(mail)) {
                deleted.addAll(contacts);
            }
        });

        sourceByMail.forEach((mail, ldapUser) -> {
            List<AddressBookContact> existing = projectionByMail.get(mail);
            if (existing == null || existing.isEmpty()) {
                added.add(toAddressBookContact(ldapUser));
                return;
            }

            // Keep a single representative for this mail and delete the surplus duplicates.
            // Favor the resource whose uid matches the LDAP user so the canonical contact survives.
            String targetUid = toAddressBookContact(ldapUser).vcardUid();
            AddressBookContact representative = existing.stream()
                .filter(contact -> contact.vcardUid().equals(targetUid))
                .findFirst()
                .orElseGet(() -> existing.stream()
                    .min(Comparator.comparing(AddressBookContact::vcardUid))
                    .orElseThrow());

            existing.stream()
                .filter(contact -> !contact.equals(representative))
                .forEach(deleted::add);

            if (isChanged(ldapUser, representative)) {
                updated.add(toAddressBookContact(ldapUser, representative));
            }
        });

        return new DomainMemberUpdate(added, deleted, updated);
    }

    private static String uid(LdapUser ldapUser) {
        return ldapUser.uid().orElse("");
    }

    private static boolean isChanged(LdapUser ldapUser, AddressBookContact davContact) {
        AddressBookContact ldapAsContact = toAddressBookContact(ldapUser, davContact);

        return !Objects.equals(ldapAsContact.givenName(), davContact.givenName())
            || !Objects.equals(ldapAsContact.familyName(), davContact.familyName())
            || !Objects.equals(ldapAsContact.mail(), davContact.mail())
            || !Objects.equals(ldapAsContact.telephoneNumber(), davContact.telephoneNumber())
            || (ldapAsContact.displayName().isPresent() && !Objects.equals(ldapAsContact.displayName(), davContact.displayName()));
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
            .familyName(ldap.sn())
            .givenName(resolveGivenName(ldap))
            .displayName(resolveDisplayName(ldap))
            .mail(ldap.mail())
            .telephoneNumber(ldap.telephoneNumber())
            .build();
    }

    private static Optional<String> resolveDisplayName(LdapUser ldap) {
        return ldap.displayName()
            .or(ldap::cn)
            .or(() -> ldap.mail().map(MailAddress::asString));
    }

    private static Optional<String> resolveGivenName(LdapUser ldap) {
        return ldap.givenName()
            .or(() -> extractGivenNameFromCommonName(ldap));
    }

    private static Optional<String> extractGivenNameFromCommonName(LdapUser ldap) {
        return ldap.cn()
            .map(cn -> Strings.CS.remove(cn, ldap.sn().orElse("")))
            .map(StringUtils::trimToNull);
    }
}