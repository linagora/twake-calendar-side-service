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
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.storage.ldap.LdapUser;

/**
 * Diff between the LDAP source of truth and the contacts currently stored in the DAV domain-members
 * address book.
 *
 * <p>Deletions and updates are expressed as {@link DomainMemberCard} so the applier can address the
 * real DAV resource by its name. A contact's resource name is NOT guaranteed to match its vCard
 * {@code UID} (resources created by other tools can use an arbitrary name), so addressing resources
 * by a reconstructed UID silently fails to update/delete them and lets duplicates pile up.
 */
public record DomainMemberUpdate(Set<AddressBookContact> added,
                                 Set<DomainMemberCard> deleted,
                                 Set<DomainMemberCard> updated) {

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainMemberUpdate.class);

    public static DomainMemberUpdate compute(Collection<LdapUser> sourceOfTruth,
                                             Collection<DomainMemberCard> projectionContent) {

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

        // The DAV projection may hold several resources for the same mail (duplicates created by
        // earlier runs or other tools). Group them by mail so surplus duplicates can be deleted.
        Map<MailAddress, List<DomainMemberCard>> projectionByMail = projectionContent.stream()
            .filter(card -> card.contact().mail().isPresent())
            .collect(Collectors.groupingBy(card -> card.contact().mail().get()));

        Set<AddressBookContact> added = new HashSet<>();
        Set<DomainMemberCard> deleted = new HashSet<>();
        Set<DomainMemberCard> updated = new HashSet<>();

        // Mails present in DAV but no longer in LDAP: delete every matching resource.
        projectionByMail.forEach((mail, cards) -> {
            if (!sourceByMail.containsKey(mail)) {
                deleted.addAll(cards);
            }
        });

        sourceByMail.forEach((mail, ldapUser) -> {
            List<DomainMemberCard> existing = projectionByMail.get(mail);
            if (existing == null || existing.isEmpty()) {
                added.add(toAddressBookContact(ldapUser));
                return;
            }

            // Keep a single deterministic representative resource for this mail (lowest resource
            // name) and delete every other duplicate resource by its real name.
            DomainMemberCard representative = existing.stream()
                .min(Comparator.comparing(DomainMemberCard::resourceName))
                .orElseThrow();

            existing.stream()
                .filter(card -> !card.resourceName().equals(representative.resourceName()))
                .forEach(deleted::add);

            if (isChanged(ldapUser, representative.contact())) {
                AddressBookContact desired = toAddressBookContact(ldapUser, representative.contact());
                updated.add(new DomainMemberCard(representative.resourceName(), desired));
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