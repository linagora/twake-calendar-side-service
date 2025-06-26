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

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.util.Port;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;

public record MailSenderConfiguration(String host,
                                      Port port,
                                      String ehlo,
                                      Optional<Username> username,
                                      Optional<String> password,
                                      boolean sslEnabled,
                                      boolean trustAllCerts,
                                      boolean startTlsEnabled,
                                      Set<MailAddress> recipientWhitelist) {

    public MailSenderConfiguration {
        Preconditions.checkArgument(username.isPresent() == password.isPresent(), "'smtp.username' and 'smtp.password' must be simultaneously set.");
        Preconditions.checkArgument(!(sslEnabled && startTlsEnabled), "'smtp.ssl.enabled' should not be set with 'smtp.starttls.enabled'");
    }

    public static MailSenderConfiguration from(Configuration configuration) {
        String host = Optional.ofNullable(configuration.getString("smtp.host", null))
            .orElseThrow(() -> new RuntimeException("'smtp.host' is compulsory"));
        String helo = Optional.ofNullable(configuration.getString("smtp.helo", null))
            .orElseThrow(() -> new RuntimeException("'smtp.helo' is compulsory"));
        Port port = Optional.ofNullable(configuration.getInteger("smtp.port", null))
            .map(Port::new)
            .orElseThrow(() -> new RuntimeException("'smtp.port' is compulsory"));
        Optional<Username> user = Optional.ofNullable(configuration.getString("smtp.username", null))
            .map(Username::of);
        Optional<String> password = Optional.ofNullable(configuration.getString("smtp.password", null));
        boolean sslEnabled = configuration.getBoolean("smtp.ssl.enabled", false);
        boolean trustAllCerts = configuration.getBoolean("smtp.ssl.trustAllCerts", false);
        boolean startTlsEnabled = configuration.getBoolean("smtp.starttls.enabled", false);

        Set<MailAddress> whitelist = Optional.ofNullable(configuration.getString("mail.imip.recipient.whitelist", null))
            .map(whitelistRaw -> Arrays.stream(whitelistRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Throwing.function(MailAddress::new))
                .collect(Collectors.toSet()))
            .orElse(ImmutableSet.of());
        return new MailSenderConfiguration(host, port, helo, user, password, sslEnabled, trustAllCerts, startTlsEnabled, whitelist);
    }
}
