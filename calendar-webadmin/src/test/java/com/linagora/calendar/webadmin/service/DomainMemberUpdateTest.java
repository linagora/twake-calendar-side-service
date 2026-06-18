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

import static com.linagora.calendar.webadmin.service.DomainMemberUpdate.toAddressBookContact;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.util.List;
import java.util.Optional;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient.DomainMemberCard;
import com.linagora.calendar.storage.ldap.LdapUser;

public class DomainMemberUpdateTest {

    private static MailAddress mailAddress(String input) {
        return Throwing.supplier(() -> new MailAddress(input)).get();
    }

    private LdapUser ldapMember(String uid, String mail, String sn, String givenName, String displayName, String tel) {
        return LdapUser.builder()
            .uid(uid)
            .cn(displayName)
            .sn(sn)
            .givenName(givenName)
            .mail(mailAddress(mail))
            .telephoneNumber(tel)
            .displayName(displayName)
            .build();
    }

    private AddressBookContact contact(String uid, String mail, String familyName, String givenName, String displayName, String tel) {
        return AddressBookContact.builder()
            .uid(uid)
            .mail(mailAddress(mail))
            .familyName(familyName)
            .givenName(givenName)
            .displayName(displayName)
            .telephoneNumber(tel)
            .build();
    }

    private DomainMemberCard davCard(String resourceName, String mail, String familyName, String givenName, String displayName, String tel) {
        return new DomainMemberCard(resourceName, contact(resourceName, mail, familyName, givenName, displayName, tel));
    }

    @Test
    void computeShouldDetectAddedMember() {
        LdapUser ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of());

        assertSoftly(softly -> {
            softly.assertThat(result.added())
                .containsExactly(contact("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123"));
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldExtractGivenNameFromCommonNameWhenGivenNameIsMissing() {
        // Given an LDAP user without givenName but with cn and sn
        LdapUser ldap = LdapUser.builder()
            .uid("uid123")
            .cn("James User2")
            .sn("User2")
            .mail(mailAddress("james-user2@james.org"))
            .telephoneNumber("123")
            .build();

        // When computing the CardDAV contact projection
        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of());

        // Then givenName is extracted from cn after removing sn, while displayName falls back to cn
        assertThat(result.added())
            .containsExactly(AddressBookContact.builder()
                .uid("uid123")
                .mail(mailAddress("james-user2@james.org"))
                .familyName("User2")
                .givenName("James")
                .displayName("James User2")
                .telephoneNumber("123")
                .build());
    }

    @Test
    void computeShouldFallbackDisplayNameToCommonNameWhenDisplayNameIsMissing() {
        // Given an LDAP user without displayName but with cn
        LdapUser ldap = LdapUser.builder()
            .uid("uid123")
            .cn("James User")
            .sn("User")
            .mail(mailAddress("james-user@james.org"))
            .telephoneNumber("123")
            .build();

        // When computing the CardDAV contact projection
        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of());

        // Then displayName falls back to cn so the vCard formatted name remains readable
        assertThat(result.added())
            .containsExactly(AddressBookContact.builder()
                .uid("uid123")
                .mail(mailAddress("james-user@james.org"))
                .familyName("User")
                .givenName("James")
                .displayName("James User")
                .telephoneNumber("123")
                .build());
    }

    @Test
    void computeShouldFallbackDisplayNameToEmailWhenNameFieldsAreMissing() {
        // Given an LDAP user without displayName, cn, givenName and sn but with mail
        LdapUser ldap = LdapUser.builder()
            .uid("uid123")
            .mail(mailAddress("user@example.com"))
            .telephoneNumber("123")
            .build();

        // When computing the CardDAV contact projection
        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of());

        // Then displayName falls back to email, without using email as givenName or familyName
        assertThat(result.added())
            .containsExactly(AddressBookContact.builder()
                .uid("uid123")
                .mail(mailAddress("user@example.com"))
                .displayName("user@example.com")
                .telephoneNumber("123")
                .build());
    }

    @Test
    void computeShouldDetectDeletedMember() {
        DomainMemberCard dav = davCard("res-1", "user2@example.com", "Tran", "B", "Tran B", "456");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
            softly.assertThat(result.deleted()).containsExactly(dav);
        });
    }

    @Test
    void computeShouldDetectNoChangeIfFieldsAreEqual() {
        LdapUser ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        DomainMemberCard dav = davCard("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDetectUpdatedMemberWhenTelephoneChanged() {
        LdapUser ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        DomainMemberCard dav = davCard("res-456", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "456");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).containsExactly(
                new DomainMemberCard("res-456", toAddressBookContact(ldap, dav.contact().uid())));
        });
    }

    @Test
    void computeShouldDetectUpdatedWhenDisplayNameChanged() {
        LdapUser ldap = ldapMember("uid123", "user3@example.com", "Le", "C", "Le C", "789");
        DomainMemberCard dav = davCard("res-456", "user3@example.com", "Le", "C", "Different Display Name", "789");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).containsExactly(
                new DomainMemberCard("res-456", toAddressBookContact(ldap, dav.contact().uid())));
        });
    }

    @Test
    void computeShouldDetectUpdatedWhenGivenNameChanged() {
        LdapUser ldap = ldapMember("uid123", "user5@example.com", "Vo", "E", "Vo E", "111");
        DomainMemberCard dav = davCard("res-456", "user5@example.com", "Vo", "E_updated", "Vo E", "111");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertThat(result.updated()).containsExactly(
            new DomainMemberCard("res-456", toAddressBookContact(ldap, dav.contact().uid())));
    }

    @Test
    void computeShouldDetectUpdatedWhenFamilyNameChanged() {
        LdapUser ldap = ldapMember("uid123", "user6@example.com", "Ho", "F", "Ho F", "222");
        DomainMemberCard dav = davCard("res-456", "user6@example.com", "Different", "F", "Ho F", "222");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertThat(result.updated()).containsExactly(
            new DomainMemberCard("res-456", toAddressBookContact(ldap, dav.contact().uid())));
    }

    @Test
    void computeShouldUpdateExistingResourceInPlaceWhenResourceNameDiffersFromUid() {
        // The DAV resource lives under a name unrelated to its vCard uid (as created by other tools).
        // The update must target the real resource name so it is rewritten in place, not duplicated.
        LdapUser ldap = ldapMember("uid123", "user7@example.com", "Smith", "Anna", "Anna Smith", "999");
        DomainMemberCard dav = new DomainMemberCard("random-resource-name",
            contact("internal-uid", "user7@example.com", "Smith", "Anna", "Outdated", "999"));

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertThat(result.updated())
            .extracting(DomainMemberCard::resourceName)
            .containsExactly("random-resource-name");
    }

    @Test
    void computeShouldDeduplicateLdapMembersSharingTheSameMail() {
        // Two distinct LDAP accounts sharing the same mail must not crash and keep a single source
        LdapUser first = ldapMember("deploy_gsafe", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", null);
        LdapUser second = ldapMember("deploy_gsafe_snapshots", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", null);

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(first, second), List.of());

        assertSoftly(softly -> {
            softly.assertThat(result.added()).containsExactly(toAddressBookContact(first, first.uid()));
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDeleteAllDavResourcesSharingAMailNoLongerInLdap() {
        // Two DAV resources share a mail no longer backed by LDAP: both must be deleted by resource name
        DomainMemberCard first = davCard("res-1", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", "111");
        DomainMemberCard second = davCard("res-2", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", "111");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(), List.of(first, second));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).containsExactlyInAnyOrder(first, second);
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDeleteSurplusDuplicateResourcesForAMailStillInLdap() {
        // Two duplicate resources for a mail still in LDAP: keep the representative, delete the surplus.
        LdapUser ldap = ldapMember("deploy_gsafe", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", "111");
        DomainMemberCard representative = davCard("res-1", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", "111");
        DomainMemberCard duplicate = davCard("res-2", "shared@example.com", "gSafe", "Deploy", "gSafe Deploy", "111");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(representative, duplicate));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).containsExactly(duplicate);
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDeleteStrictlyIdenticalDuplicateResources() {
        // Two resources with identical vCard content (same uid, same fields) but distinct resource names.
        LdapUser ldap = ldapMember("frederic", "frederic@example.com", "Martin", "Frederic", "Frederic Martin", "1");
        AddressBookContact identical = contact("shared-uid", "frederic@example.com", "Martin", "Frederic", "Frederic Martin", "1");
        DomainMemberCard cardA = new DomainMemberCard("res-a", identical);
        DomainMemberCard cardB = new DomainMemberCard("res-b", identical);

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(cardA, cardB));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            // res-a is kept (lowest resource name), res-b deleted even though content is identical
            softly.assertThat(result.deleted()).containsExactly(cardB);
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDetectAddedDeletedAndUpdatedMembers() {
        LdapUser addedLdap = ldapMember("uid1", "added@example.com", "Nguyen", "A", "Nguyen A", "111");
        DomainMemberCard deletedDav = davCard("res-2", "deleted@example.com", "Tran", "B", "Tran B", "222");
        LdapUser updatedLdap = ldapMember("uid3", "common@example.com", "Le", "C", "Le C", "333");
        DomainMemberCard outdatedDav = davCard("res-4", "common@example.com", "Le", "C", "Different Display", "333");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(addedLdap, updatedLdap),
            List.of(deletedDav, outdatedDav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).containsExactly(toAddressBookContact(addedLdap, addedLdap.uid()));
            softly.assertThat(result.deleted()).containsExactly(deletedDav);
            softly.assertThat(result.updated()).containsExactly(
                new DomainMemberCard("res-4", toAddressBookContact(updatedLdap, outdatedDav.contact().uid())));
        });
    }

    @Test
    void computeShouldHandleMultipleAddedDeletedAndUpdatedMembers() {
        // Added members
        LdapUser added1 = ldapMember("uid1", "added1@example.com", "Nguyen", "A", "Nguyen A", "111");
        LdapUser added2 = ldapMember("uid2", "added2@example.com", "Tran", "B", "Tran B", "222");

        // Deleted members
        DomainMemberCard deleted1 = davCard("res-3", "deleted1@example.com", "Le", "C", "Le C", "333");
        DomainMemberCard deleted2 = davCard("res-4", "deleted2@example.com", "Pham", "D", "Pham D", "444");

        // Updated members (same email, different fields)
        LdapUser updated1Ldap = ldapMember("uid5", "update1@example.com", "Vo", "E", "Vo E", "555");
        DomainMemberCard outdated1Dav = davCard("res-6", "update1@example.com", "Vo", "E_updated", "Vo E", "555");

        LdapUser updated2Ldap = ldapMember("uid7", "update2@example.com", "Dang", "F", "Dang F", "666");
        DomainMemberCard outdated2Dav = davCard("res-8", "update2@example.com", "Dang", "F", "Different Display", "666");

        DomainMemberUpdate result = DomainMemberUpdate.compute(
            List.of(added1, added2, updated1Ldap, updated2Ldap),
            List.of(deleted1, deleted2, outdated1Dav, outdated2Dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).containsExactlyInAnyOrder(
                toAddressBookContact(added1, added1.uid()),
                toAddressBookContact(added2, added2.uid()));
            softly.assertThat(result.deleted()).containsExactlyInAnyOrder(deleted1, deleted2);
            softly.assertThat(result.updated()).containsExactlyInAnyOrder(
                new DomainMemberCard("res-6", toAddressBookContact(updated1Ldap, outdated1Dav.contact().uid())),
                new DomainMemberCard("res-8", toAddressBookContact(updated2Ldap, outdated2Dav.contact().uid())));
        });
    }

    @Test
    void toAddressBookContactShouldBuildFromUid() {
        LdapUser ldap = ldapMember("uid123", "user@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");

        assertThat(toAddressBookContact(ldap, Optional.of("uid123")))
            .isEqualTo(contact("uid123", "user@example.com", "Nguyen", "Van A", "Nguyen Van A", "123"));
    }

}
