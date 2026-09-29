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

package com.linagora.calendar.commoncontacts.api;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.util.Port;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

public record CommonContactsApiConfiguration(Optional<Integer> port, Set<Secret> secrets) {
    public static final String PORT_PROPERTY = "common.contact.api.port";
    public static final String SECRETS_PROPERTY = "common.contact.api.secrets";
    public static final int RANDOM_PORT = 0;

    private static final Splitter SECRET_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    public record Secret(String value) {
        public Secret {
            Preconditions.checkArgument(!value.isBlank(), "Secret must not be blank");
        }

        @Override
        public String toString() {
            return "Secret{***}";
        }
    }

    public static final CommonContactsApiConfiguration DISABLED = new CommonContactsApiConfiguration(Optional.empty(), ImmutableSet.of());

    public static CommonContactsApiConfiguration parse(Configuration configuration) {
        return new CommonContactsApiConfiguration(
            Optional.ofNullable(configuration.getInteger(PORT_PROPERTY, null)),
            parseSecrets(configuration));
    }

    // The properties file may or may not split values on comas depending on its list delimiter handler: handle both
    private static Set<Secret> parseSecrets(Configuration configuration) {
        return Arrays.stream(configuration.getStringArray(SECRETS_PROPERTY))
            .flatMap(SECRET_SPLITTER::splitToStream)
            .map(Secret::new)
            .collect(ImmutableSet.toImmutableSet());
    }

    public CommonContactsApiConfiguration {
        port.ifPresent(value -> Preconditions.checkArgument(value == RANDOM_PORT || Port.isValid(value),
            "'%s' must be a valid port, got %s", PORT_PROPERTY, value));
        Preconditions.checkArgument(port.isEmpty() || !secrets.isEmpty(),
            "'%s' must be configured when '%s' is set", SECRETS_PROPERTY, PORT_PROPERTY);
        secrets = ImmutableSet.copyOf(secrets);
    }

    public boolean enabled() {
        return port.isPresent();
    }
}
