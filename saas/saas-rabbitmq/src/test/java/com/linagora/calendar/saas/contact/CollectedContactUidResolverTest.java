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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.google.common.hash.Hashing;

import it.cnr.iit.jscontact.tools.dto.Card;

class CollectedContactUidResolverTest {
    private final CollectedContactConverter.ContactUidResolver testee = new CollectedContactConverter.ContactUidResolver();

    @Test
    void shouldKeepExistingUid() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid"
            }
            """)).value())
            .as("Keeps an explicit UID")
            .isEqualTo("contact-uid");
    }

    @Test
    void shouldKeepExistingUidWhenEmailIsAvailable() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "uid": "contact-uid",
              "emails": {
                "main": {
                  "@type": "EmailAddress",
                  "address": "bob@example.com"
                }
              }
            }
            """)).value())
            .as("Keeps an explicit UID instead of generating one from email")
            .isEqualTo("contact-uid");
    }

    @Test
    void shouldGenerateUidFromPreferredEmail() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "personal": {
                  "@type": "EmailAddress",
                  "address": "personal@example.com",
                  "pref": 2
                },
                "main": {
                  "@type": "EmailAddress",
                  "address": "bob@example.com",
                  "pref": 1
                }
              }
            }
            """)).value())
            .as("Uses the email with the lowest pref value")
            .isEqualTo(sha1("bob@example.com"));
    }

    @Test
    void shouldGenerateUidFromAlphabeticallyFirstEmailWhenPreferenceIsMissing() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "first-key": {
                  "@type": "EmailAddress",
                  "address": "zulu@example.com"
                },
                "last-key": {
                  "@type": "EmailAddress",
                  "address": "alpha@example.com"
                }
              }
            }
            """)).value())
            .as("Uses the alphabetically first email when no preference is set")
            .isEqualTo(sha1("alpha@example.com"));
    }

    @Test
    void shouldGenerateUidFromMatrixIdWhenEmailIsMissing() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@bob:example.com"
                }
              }
            }
            """)).value())
            .as("Normalizes the Matrix ID as an email address before hashing it")
            .isEqualTo(sha1("bob@example.com"));
    }

    @Test
    void shouldGenerateUidFromPreferredMatrixId() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "second": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@second:example.com",
                  "pref": 2
                },
                "first": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@first:example.com",
                  "pref": 1
                }
              }
            }
            """)).value())
            .as("Uses the Matrix ID with the lowest pref value")
            .isEqualTo(sha1("first@example.com"));
    }

    @Test
    void shouldGenerateUidFromAlphabeticallyFirstMatrixIdWhenPreferenceIsMissing() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "first-key": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@zulu:example.com"
                },
                "last-key": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@alpha:example.com"
                }
              }
            }
            """)).value())
            .as("Uses the alphabetically first Matrix ID when no preference is set")
            .isEqualTo(sha1("alpha@example.com"));
    }

    @Test
    void shouldGenerateUidFromEmailWhenMatrixIdAndPhoneAreAlsoAvailable() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "emails": {
                "main": {
                  "@type": "EmailAddress",
                  "address": "bob@example.com"
                }
              },
              "onlineServices": {
                "matrix": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@bob:matrix.example.com"
                }
              },
              "phones": {
                "main": {
                  "@type": "Phone",
                  "number": "+33123456789"
                }
              }
            }
            """)).value())
            .as("Uses email before Matrix ID and phone number")
            .isEqualTo(sha1("bob@example.com"));
    }

    @Test
    void shouldGenerateUidFromMatrixIdWhenPhoneIsAlsoAvailable() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "onlineServices": {
                "matrix": {
                  "@type": "OnlineService",
                  "service": "matrix",
                  "user": "@bob:matrix.example.com"
                }
              },
              "phones": {
                "main": {
                  "@type": "Phone",
                  "number": "+33123456789"
                }
              }
            }
            """)).value())
            .as("Uses Matrix ID before phone number")
            .isEqualTo(sha1("bob@matrix.example.com"));
    }

    @Test
    void shouldGenerateUidFromPhoneWhenEmailAndMatrixIdAreMissing() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "phones": {
                "main": {
                  "@type": "Phone",
                  "number": "+33123456789"
                }
              }
            }
            """)).value())
            .as("Falls back to the phone number when email and Matrix ID are missing")
            .isEqualTo(sha1("+33123456789"));
    }

    @Test
    void shouldGenerateUidFromPreferredPhoneNumber() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "phones": {
                "second": {
                  "@type": "Phone",
                  "number": "+33612345678",
                  "pref": 2
                },
                "first": {
                  "@type": "Phone",
                  "number": "+33123456789",
                  "pref": 1
                }
              }
            }
            """)).value())
            .as("Uses the phone number with the lowest pref value")
            .isEqualTo(sha1("+33123456789"));
    }

    @Test
    void shouldGenerateUidFromAlphabeticallyFirstPhoneNumberWhenPreferenceIsMissing() throws Exception {
        assertThat(testee.resolve(Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0",
              "phones": {
                "first-key": {
                  "@type": "Phone",
                  "number": "+33612345678"
                },
                "last-key": {
                  "@type": "Phone",
                  "number": "+33123456789"
                }
              }
            }
            """)).value())
            .as("Uses the alphabetically first phone number when no preference is set")
            .isEqualTo(sha1("+33123456789"));
    }

    @Test
    void shouldThrowWhenUidCannotBeGenerated() throws Exception {
        Card card = Card.toJSCard("""
            {
              "@type": "Card",
              "version": "2.0"
            }
            """);

        assertThatThrownBy(() -> testee.resolve(card))
            .as("Fails when no UID source is available")
            .isInstanceOf(CollectedContactConversionException.class)
            .hasMessage("Cannot generate contact UID: missing email, Matrix ID and phone number");
    }

    @SuppressWarnings("deprecation")
    private static String sha1(String value) {
        return Hashing.sha1()
            .hashString(value, StandardCharsets.UTF_8)
            .toString();
    }
}
