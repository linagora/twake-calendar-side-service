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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.saas.SaaSCalendarSubscriptionDeserializer.SaaSCalendarSubscriptionMessageParseException;

class SaaSCalendarSubscriptionDeserializerTest {

    @Nested
    class DomainMessageTests {

        @Test
        void shouldParseDomainMessageWithCalendarFeature() {
            String json = """
                {
                    "domain": "toto.tld",
                    "mailDnsConfigurationValidated": true,
                    "features": {
                        "calendar": {
                            "enabled": true
                        }
                    }
                }
                """;

            DomainSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json);

            assertThat(message.domainObject()).isEqualTo(Domain.of("toto.tld"));
            assertThat(message.mailDnsConfigurationValidated().get()).isTrue();
            assertThat(message.hasCalendarFeature()).isTrue();
        }

        @Test
        void shouldParseDomainMessageWithoutCalendarFeature() {
            String json = """
                {
                    "domain": "toto.tld",
                    "mailDnsConfigurationValidated": true,
                    "features": {
                        "mail": {}
                    }
                }
                """;

            DomainSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json);

            assertThat(message.domainObject()).isEqualTo(Domain.of("toto.tld"));
            assertThat(message.hasCalendarFeature()).isFalse();
        }

        @Test
        void shouldParseDomainMessageWithEmptyFeatures() {
            String json = """
                {
                    "domain": "toto.tld",
                    "mailDnsConfigurationValidated": false,
                    "features": {}
                }
                """;

            DomainSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json);

            assertThat(message.domainObject()).isEqualTo(Domain.of("toto.tld"));
            assertThat(message.mailDnsConfigurationValidated().get()).isFalse();
            assertThat(message.hasCalendarFeature()).isFalse();
        }

        @Test
        void shouldParseDomainMessageWithNullCalendarFeature() {
            String json = """
                {
                    "domain": "toto.tld",
                    "mailDnsConfigurationValidated": true,
                    "features": {
                        "calendar": null
                    }
                }
                """;

            DomainSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json);

            assertThat(message.hasCalendarFeature()).isFalse();
        }

        @Test
        void shouldParseDomainMessageFromBytes() {
            String json = """
                {
                    "domain": "example.com",
                    "mailDnsConfigurationValidated": true,
                    "features": {
                        "calendar": {}
                    }
                }
                """;

            DomainSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseDomainMessage(
                json.getBytes(StandardCharsets.UTF_8));

            assertThat(message.domainObject()).isEqualTo(Domain.of("example.com"));
            assertThat(message.hasCalendarFeature()).isTrue();
        }

        @Test
        void shouldThrowWhenDomainIsMissing() {
            String json = """
                {
                    "mailDnsConfigurationValidated": true,
                    "features": {
                        "calendar": {}
                    }
                }
                """;

            assertThatThrownBy(() -> SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json))
                .isInstanceOf(SaaSCalendarSubscriptionMessageParseException.class);
        }

        @Test
        void shouldThrowWhenJsonIsInvalid() {
            String json = "not a json";

            assertThatThrownBy(() -> SaaSCalendarSubscriptionDeserializer.parseDomainMessage(json))
                .isInstanceOf(SaaSCalendarSubscriptionMessageParseException.class);
        }
    }

    @Nested
    class UserMessageTests {

        @Test
        void shouldParseUserMessageWithCalendarFeature() {
            String json = """
                {
                    "internalEmail": "bob@toto.tld",
                    "isPaying": true,
                    "canUpgrade": true,
                    "features": {
                        "calendar": {
                            "enabled": true
                        }
                    }
                }
                """;

            UserSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseUserMessage(json);

            assertThat(message.username()).isEqualTo(Username.of("bob@toto.tld"));
            assertThat(message.internalEmail()).isEqualTo("bob@toto.tld");
            assertThat(message.isPaying()).isTrue();
            assertThat(message.canUpgrade()).isTrue();
            assertThat(message.hasCalendarFeature()).isTrue();
        }

        @Test
        void shouldParseUserMessageWithoutCalendarFeature() {
            String json = """
                {
                    "internalEmail": "bob@toto.tld",
                    "isPaying": true,
                    "canUpgrade": true,
                    "features": {
                        "mail": {}
                    }
                }
                """;

            UserSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseUserMessage(json);

            assertThat(message.username()).isEqualTo(Username.of("bob@toto.tld"));
            assertThat(message.hasCalendarFeature()).isFalse();
        }

        @Test
        void shouldParseUserMessageFromBytes() {
            String json = """
                {
                    "internalEmail": "alice@example.com",
                    "isPaying": false,
                    "canUpgrade": true,
                    "features": {
                        "calendar": {}
                    }
                }
                """;

            UserSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseUserMessage(
                json.getBytes(StandardCharsets.UTF_8));

            assertThat(message.username()).isEqualTo(Username.of("alice@example.com"));
            assertThat(message.isPaying()).isFalse();
            assertThat(message.canUpgrade()).isTrue();
            assertThat(message.hasCalendarFeature()).isTrue();
        }

        @Test
        void shouldThrowWhenInternalEmailIsMissing() {
            String json = """
                {
                    "isPaying": true,
                    "canUpgrade": true,
                    "features": {
                        "calendar": {}
                    }
                }
                """;

            assertThatThrownBy(() -> SaaSCalendarSubscriptionDeserializer.parseUserMessage(json))
                .isInstanceOf(SaaSCalendarSubscriptionMessageParseException.class);
        }

        @Test
        void shouldThrowWhenJsonIsInvalid() {
            String json = "invalid json";

            assertThatThrownBy(() -> SaaSCalendarSubscriptionDeserializer.parseUserMessage(json))
                .isInstanceOf(SaaSCalendarSubscriptionMessageParseException.class);
        }

        @Test
        void shouldIgnoreUnknownFields() {
            String json = """
                {
                    "internalEmail": "bob@toto.tld",
                    "isPaying": true,
                    "canUpgrade": true,
                    "unknownField": "value",
                    "features": {
                        "calendar": {},
                        "unknownFeature": {}
                    }
                }
                """;

            UserSubscriptionMessage message = SaaSCalendarSubscriptionDeserializer.parseUserMessage(json);

            assertThat(message.username()).isEqualTo(Username.of("bob@toto.tld"));
            assertThat(message.hasCalendarFeature()).isTrue();
        }
    }
}
