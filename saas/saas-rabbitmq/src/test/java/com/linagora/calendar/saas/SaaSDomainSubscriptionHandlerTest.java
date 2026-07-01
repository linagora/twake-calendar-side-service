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

package com.linagora.calendar.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.apache.james.core.Domain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;

class SaaSDomainSubscriptionHandlerTest {

    private SaaSDomainSubscriptionHandler testee;
    private OpenPaaSDomainDAO domainDAO;

    @BeforeEach
    void setUp() {
        domainDAO = new MemoryOpenPaaSDomainDAO();
        testee = new SaaSDomainSubscriptionHandler(domainDAO);
    }

    @Test
    void shouldCreateDomainOnDnsConfigurationMessageWhenMailDnsValidated() {
        // `domain.dns.configuration.status` message: carries the DNS status, no `features`.
        String json = """
            {
                "organizationId": "businesse7f351",
                "domain": "toto.tld",
                "dnsOwnershipValidated": true,
                "mailDnsConfigurationValidated": true,
                "chatDnsConfigurationValidated": false
            }
            """;

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSDomain domain = domainDAO.retrieve(Domain.of("toto.tld")).block();
        assertThat(domain).isNotNull();
        assertThat(domain.domain()).isEqualTo(Domain.of("toto.tld"));
    }

    @Test
    void shouldNotCreateDomainOnSubscriptionMessageAsItCarriesNoDnsStatus() {
        // `domain.subscription.changed` message: carries `features` but no DNS status, so the domain must
        // not be created yet (it will be created upon the later DNS configuration message).
        String json = """
            {
                "domain": "toto.tld",
                "features": {
                    "calendar": {}
                }
            }
            """;

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSDomain domain = domainDAO.retrieve(Domain.of("toto.tld")).block();
        assertThat(domain).isNull();
    }

    @Test
    void shouldNotCreateDomainWhenMailDnsNotValidated() {
        String json = """
            {
                "organizationId": "businesse7f351",
                "domain": "toto.tld",
                "dnsOwnershipValidated": true,
                "mailDnsConfigurationValidated": false,
                "chatDnsConfigurationValidated": false
            }
            """;

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSDomain domain = domainDAO.retrieve(Domain.of("toto.tld")).block();
        assertThat(domain).isNull();
    }

    @Test
    void shouldNotCreateDomainWhenMailDnsStatusIsMissing() {
        String json = """
            {
                "organizationId": "businesse7f351",
                "domain": "toto.tld",
                "dnsOwnershipValidated": true,
                "chatDnsConfigurationValidated": false
            }
            """;

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        OpenPaaSDomain domain = domainDAO.retrieve(Domain.of("toto.tld")).block();
        assertThat(domain).isNull();
    }

    @Test
    void shouldNotFailWhenDomainAlreadyExists() {
        Domain domain = Domain.of("existing.tld");
        domainDAO.add(domain).block();

        String json = """
            {
                "organizationId": "businesse7f351",
                "domain": "existing.tld",
                "dnsOwnershipValidated": true,
                "mailDnsConfigurationValidated": true,
                "chatDnsConfigurationValidated": false
            }
            """;

        testee.handleMessage(json.getBytes(StandardCharsets.UTF_8)).block();

        // Should not throw, domain should still exist
        OpenPaaSDomain result = domainDAO.retrieve(domain).block();
        assertThat(result).isNotNull();
    }

    @Test
    void shouldHandleMultipleDomains() {
        String json1 = """
            {
                "domain": "domain1.tld",
                "mailDnsConfigurationValidated": true
            }
            """;
        String json2 = """
            {
                "domain": "domain2.tld",
                "mailDnsConfigurationValidated": true
            }
            """;

        testee.handleMessage(json1.getBytes(StandardCharsets.UTF_8)).block();
        testee.handleMessage(json2.getBytes(StandardCharsets.UTF_8)).block();

        assertThat(domainDAO.retrieve(Domain.of("domain1.tld")).block()).isNotNull();
        assertThat(domainDAO.retrieve(Domain.of("domain2.tld")).block()).isNotNull();
    }
}
