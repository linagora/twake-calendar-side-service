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

package com.linagora.calendar.saas.contact;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience.Domain;
import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;

class CommonContactOutboundEventConverterTest {
    private static final String CONTACT_PATH = "addressbooks/principal/collected/contact-resource.vcf";

    private CommonContactEventConverter testee;
    private MemoryOpenPaaSDomainDAO domainDAO;
    private MemoryOpenPaaSUserDAO userDAO;

    @BeforeEach
    void setUp() {
        userDAO = new MemoryOpenPaaSUserDAO();
        domainDAO = new MemoryOpenPaaSDomainDAO();
        testee = new CommonContactEventConverter(userDAO, domainDAO);
    }

    @Test
    void shouldConvertContactData() throws Exception {
        OpenPaaSUser owner = userDAO.add(Username.of("owner@linagora.com")).block();

        CommonContactOutboundEvent event = testee.convert(CommonContactNotificationConsumer.Queue.CREATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/users/" + owner.id().value(), """
                BEGIN:VCARD
                VERSION:4.0
                UID:contact-uid
                FN:Alice
                END:VCARD
                """)).block();

        assertThatJson(new String(event.serialize(), StandardCharsets.UTF_8)).isEqualTo("""
            {
              "audience": { "user": "owner@linagora.com" },
              "action": "ADD",
              "path": "addressbooks/principal/collected/contact-resource.vcf",
              "uid": "contact-uid",
              "payload": {
                "@type": "Card",
                "version": "2.0",
                "uid": "contact-uid",
                "name": {
                  "@type": "Name",
                  "full": "Alice"
                },
                "vCardProps": [["version", {}, "text", "4.0"]]
              }
            }
            """);
    }

    @Test
    void shouldResolveUserAudienceFromOwner() {
        OpenPaaSUser owner = userDAO.add(Username.of("owner@linagora.com")).block();

        CommonContactOutboundEvent event = testee.convert(CommonContactNotificationConsumer.Queue.CREATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/users/" + owner.id().value(), """
                BEGIN:VCARD
                VERSION:4.0
                UID:contact-uid
                FN:Alice
                END:VCARD
                """)).block();

        assertThat(event.audience()).isEqualTo(new CommonContactOutboundEvent.Audience.User(owner.username()));
    }

    @Test
    void shouldResolveDomainAudienceFromDomainOwner() {
        OpenPaaSDomain domain = domainDAO.add(org.apache.james.core.Domain.of("linagora.com")).block();

        CommonContactOutboundEvent event = testee.convert(CommonContactNotificationConsumer.Queue.CREATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/domains/" + domain.id().value(), """
                BEGIN:VCARD
                VERSION:4.0
                UID:contact-uid
                FN:Alice
                END:VCARD
                """)).block();

        assertThat(event.audience()).isEqualTo(new Domain(domain.domain()));
    }

    @Test
    void shouldThrowWhenContactDataHasNoUid() {
        OpenPaaSUser owner = userDAO.add(Username.of("owner@linagora.com")).block();

        assertThatThrownBy(() -> testee.convert(CommonContactNotificationConsumer.Queue.UPDATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/users/" + owner.id().value(), """
                BEGIN:VCARD
                VERSION:4.0
                FN:Alice
                END:VCARD
                """)).block())
            .isInstanceOf(CommonContactEventConversionException.class);
    }

    @Test
    void shouldThrowWhenUserOwnerCannotBeResolved() {
        assertThatThrownBy(() -> testee.convert(CommonContactNotificationConsumer.Queue.CREATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/users/unknown", """
                BEGIN:VCARD
                VERSION:4.0
                UID:contact-uid
                FN:Alice
                END:VCARD
                """)).block())
            .isInstanceOf(CommonContactEventConversionException.class);
    }

    @Test
    void shouldThrowWhenVCardCannotBeConverted() {
        OpenPaaSUser owner = userDAO.add(Username.of("owner@linagora.com")).block();

        assertThatThrownBy(() -> testee.convert(CommonContactNotificationConsumer.Queue.CREATE,
            new SabreContactNotificationDTO(CONTACT_PATH, "principals/users/" + owner.id().value(), "not-a-vcard")).block())
            .isInstanceOf(CommonContactEventConversionException.class);
    }

}
