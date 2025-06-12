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

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.storage.ldap.LdapDomainMember;

public class DomainMemberUpdateTest {

    private static MailAddress mailAddress(String input) {
        return Throwing.supplier(() -> new MailAddress(input)).get();
    }

    private LdapDomainMember ldapMember(String uid, String mail, String sn, String givenName, String displayName, String tel) {
        return LdapDomainMember.builder()
            .uid(uid)
            .cn(displayName)
            .sn(sn)
            .givenName(givenName)
            .mail(mailAddress(mail))
            .telephoneNumber(tel)
            .displayName(displayName)
            .build();
    }

    private AddressBookContact davCard(String uid, String mail, String familyName, String givenName, String displayName, String tel) {
        return AddressBookContact.builder()
            .uid(uid)
            .mail(mailAddress(mail))
            .familyName(familyName)
            .givenName(givenName)
            .displayName(displayName)
            .telephoneNumber(tel)
            .build();
    }

    @Test
    void computeShouldDetectAddedMember() {
        LdapDomainMember ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of());

        assertSoftly(softly -> {
            softly.assertThat(result.added())
                .containsExactly(davCard("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123"));
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDetectDeletedMember() {
        AddressBookContact dav = davCard("uid123", "user2@example.com", "Tran", "B", "Tran B", "456");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
            softly.assertThat(result.deleted()).containsExactly(dav);
        });
    }

    @Test
    void computeShouldDetectNoChangeIfFieldsAreEqual() {
        LdapDomainMember ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        AddressBookContact dav = davCard("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).isEmpty();
        });
    }

    @Test
    void computeShouldDetectUpdatedMemberWhenTelephoneChanged() {
        LdapDomainMember ldap = ldapMember("uid123", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "123");
        AddressBookContact dav = davCard("uid456", "user1@example.com", "Nguyen", "Van A", "Nguyen Van A", "456");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).containsExactly(toAddressBookContact(ldap, dav.uid()));
        });
    }

    @Test
    void computeShouldDetectUpdatedWhenDisplayNameChanged() {
        LdapDomainMember ldap = ldapMember("uid123", "user3@example.com", "Le", "C", "Le C", "789");
        AddressBookContact dav = davCard("uid456", "user3@example.com", "Le", "C", "Different Display Name", "789");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).isEmpty();
            softly.assertThat(result.deleted()).isEmpty();
            softly.assertThat(result.updated()).containsExactly(toAddressBookContact(ldap, dav.uid()));
        });
    }

    @Test
    void computeShouldDetectUpdatedWhenGivenNameChanged() {
        LdapDomainMember ldap = ldapMember("uid123", "user5@example.com", "Vo", "E", "Vo E", "111");
        AddressBookContact dav = davCard("uid456", "user5@example.com", "Vo", "E_updated", "Vo E", "111");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertThat(result.updated()).containsExactly(toAddressBookContact(ldap, dav.uid()));
    }

    @Test
    void computeShouldDetectUpdatedWhenFamilyNameChanged() {
        LdapDomainMember ldap = ldapMember("uid123", "user6@example.com", "Ho", "F", "Ho F", "222");
        AddressBookContact dav = davCard("uid456", "user6@example.com", "Different", "F", "Ho F", "222");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(ldap), List.of(dav));

        assertThat(result.updated()).containsExactly(toAddressBookContact(ldap, dav.uid()));
    }

    @Test
    void computeShouldDetectAddedDeletedAndUpdatedMembers() {
        LdapDomainMember addedLdap = ldapMember("uid1", "added@example.com", "Nguyen", "A", "Nguyen A", "111");
        AddressBookContact deletedDav = davCard("uid2", "deleted@example.com", "Tran", "B", "Tran B", "222");
        LdapDomainMember updatedLdap = ldapMember("uid3", "common@example.com", "Le", "C", "Le C", "333");
        AddressBookContact outdatedDav = davCard("uid4", "common@example.com", "Le", "C", "Different Display", "333");

        DomainMemberUpdate result = DomainMemberUpdate.compute(List.of(addedLdap, updatedLdap),
            List.of(deletedDav, outdatedDav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).containsExactly(toAddressBookContact(addedLdap, addedLdap.uid()));
            softly.assertThat(result.deleted()).containsExactly(deletedDav);
            softly.assertThat(result.updated()).containsExactly(toAddressBookContact(updatedLdap, outdatedDav.uid()));
        });
    }

    @Test
    void computeShouldHandleMultipleAddedDeletedAndUpdatedMembers() {
        // Added members
        LdapDomainMember added1 = ldapMember("uid1", "added1@example.com", "Nguyen", "A", "Nguyen A", "111");
        LdapDomainMember added2 = ldapMember("uid2", "added2@example.com", "Tran", "B", "Tran B", "222");

        // Deleted members
        AddressBookContact deleted1 = davCard("uid3", "deleted1@example.com", "Le", "C", "Le C", "333");
        AddressBookContact deleted2 = davCard("uid4", "deleted2@example.com", "Pham", "D", "Pham D", "444");

        // Updated members (same email, different fields)
        LdapDomainMember updated1Ldap = ldapMember("uid5", "update1@example.com", "Vo", "E", "Vo E", "555");
        AddressBookContact outdated1Dav = davCard("uid6", "update1@example.com", "Vo", "E_updated", "Vo E", "555");

        LdapDomainMember updated2Ldap = ldapMember("uid7", "update2@example.com", "Dang", "F", "Dang F", "666");
        AddressBookContact outdated2Dav = davCard("uid8", "update2@example.com", "Dang", "F", "Different Display", "666");

        DomainMemberUpdate result = DomainMemberUpdate.compute(
            List.of(added1, added2, updated1Ldap, updated2Ldap),
            List.of(deleted1, deleted2, outdated1Dav, outdated2Dav));

        assertSoftly(softly -> {
            softly.assertThat(result.added()).containsExactlyInAnyOrder(
                toAddressBookContact(added1, added1.uid()),
                toAddressBookContact(added2, added2.uid()));
            softly.assertThat(result.deleted()).containsExactlyInAnyOrder(deleted1, deleted2);
            softly.assertThat(result.updated()).containsExactlyInAnyOrder(
                toAddressBookContact(updated1Ldap, outdated1Dav.uid()),
                toAddressBookContact(updated2Ldap, outdated2Dav.uid()));
        });
    }

}
