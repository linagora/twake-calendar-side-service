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

package com.linagora.calendar.smtp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.apache.james.util.Port;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MailSenderTlsTest {

    private static MailSenderConfiguration configuration(boolean sslEnabled, boolean startTlsEnabled, boolean trustAllCerts) {
        return new MailSenderConfiguration("smtp.example.com", Port.of(465), "localhost",
            Optional.empty(), Optional.empty(), sslEnabled, trustAllCerts, startTlsEnabled);
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true", "false, false"})
    void clientShouldCheckTheServerHostName(boolean sslEnabled, boolean startTlsEnabled) throws Exception {
        assertThat(MailSender.Factory.Default.newClient(configuration(sslEnabled, startTlsEnabled, false)).isEndpointCheckingEnabled())
            .isTrue();
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true"})
    void verifyHostnameFalseShouldSkipTheHostNameCheck(boolean sslEnabled, boolean startTlsEnabled) throws Exception {
        MailSenderConfiguration configuration = new MailSenderConfiguration("smtp.example.com", Port.of(465), "localhost",
            Optional.empty(), Optional.empty(), sslEnabled, false, startTlsEnabled, false);

        assertThat(MailSender.Factory.Default.newClient(configuration).isEndpointCheckingEnabled())
            .isFalse();
    }

    @Test
    void verifyHostnameShouldDefaultToTrue() {
        PropertiesConfiguration properties = new PropertiesConfiguration();
        properties.addProperty("smtp.host", "smtp.example.com");
        properties.addProperty("smtp.port", 465);
        properties.addProperty("smtp.helo", "localhost");

        assertThat(MailSenderConfiguration.from(properties).verifyHostname()).isTrue();
    }

    @Test
    void verifyHostnameShouldBeReadFromTheConfiguration() {
        PropertiesConfiguration properties = new PropertiesConfiguration();
        properties.addProperty("smtp.host", "smtp.example.com");
        properties.addProperty("smtp.port", 465);
        properties.addProperty("smtp.helo", "localhost");
        properties.addProperty("smtp.ssl.verifyHostname", false);

        assertThat(MailSenderConfiguration.from(properties).verifyHostname()).isFalse();
    }

    @ParameterizedTest
    @CsvSource({"true, false", "false, true"})
    void trustAllCertsShouldSkipEveryCheck(boolean sslEnabled, boolean startTlsEnabled) throws Exception {
        assertThat(MailSender.Factory.Default.newClient(configuration(sslEnabled, startTlsEnabled, true)).isEndpointCheckingEnabled())
            .isFalse();
    }
}
