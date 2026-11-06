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

package com.linagora.calendar.saas.drive.test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.linagora.calendar.saas.drive.configuration.ClouderyLookup;
import com.linagora.calendar.saas.drive.tdrive.ClouderyTDriveInstanceResolver;

class ClouderyTDriveInstanceResolverTest {
    private static final String LOOKUP_PATH = "/api/v2/instances";
    private static final String LOOKUP_TOKEN = "secret-lookup-token";
    private static final Username USER = Username.of("alice.smith+calendar@other.example");

    @RegisterExtension
    static WireMockExtension broker = WireMockExtension.newInstance()
        .options(WireMockConfiguration.options().dynamicPort())
        .build();

    private ClouderyTDriveInstanceResolver resolver() {
        ClouderyLookup lookup = new ClouderyLookup(URI.create(broker.baseUrl() + LOOKUP_PATH), LOOKUP_TOKEN);
        return new ClouderyTDriveInstanceResolver(lookup, Duration.ofSeconds(3), false);
    }

    @Test
    void shouldLookUpTheFullEmailWithThePartnerCredential() {
        broker.stubFor(get(urlPathEqualTo(LOOKUP_PATH))
            .withQueryParam("email", equalTo(USER.asString()))
            .withQueryParam("limit", equalTo("2"))
            .withHeader("Authorization", equalTo("Bearer " + LOOKUP_TOKEN))
            .willReturn(aResponse().withBody("""
                {"count": 1, "items": [{"fqdn": "alice.example.com", "internal_email": "<email>"}]}
                """.replace("<email>", USER.asString()))));

        assertThat(resolver().resolve(USER).block()).isEqualTo("alice.example.com");
    }

    @ParameterizedTest
    @CsvSource(value = {
        "1, alice.smith+calendar@wrong.example, INVALID_RESPONSE",
        "1, alicesmith+calendar@other.example, INVALID_RESPONSE",
        "1, , INVALID_RESPONSE",
        "2, alice.smith+calendar@other.example, INVALID_ATTACHMENT"
    })
    void shouldRejectUnverifiedOrAmbiguousMatches(int count, String owner, Reason reason) {
        stubLookup(200, """
            {"count": <count>, "items": [{"fqdn": "alice.example.com", "internal_email": "<owner>"}]}
            """.replace("<count>", String.valueOf(count)).replace("<owner>", owner == null ? "" : owner));

        assertThatThrownBy(() -> resolver().resolve(USER).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }

    @Test
    void shouldRejectMissingInstance() {
        stubLookup(200, """
            {"count": 0, "items": []}
            """);

        assertThatThrownBy(() -> resolver().resolve(USER).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class,
                error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_ATTACHMENT));
    }

    @Test
    void shouldRejectDeniedLookup() {
        stubLookup(401, "");

        assertThatThrownBy(() -> resolver().resolve(USER).block())
            .isInstanceOfSatisfying(AttachmentStorageException.class, error -> assertThat(error.status()).isEqualTo(401));
    }

    private void stubLookup(int status, String body) {
        broker.stubFor(get(urlPathEqualTo(LOOKUP_PATH)).willReturn(aResponse().withStatus(status).withBody(body)));
    }
}
