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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.smtp.i18n.I18NTranslator;

class HtmlAddressBookDelegationRenderTest {
    private static final String CONTACTS_URL = "https://contacts.example.org/";

    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "vi", "de", "es", "it", "ru"})
    void shouldRenderContactsLinkAndEscapeOwnerAndAddressBookNames(String language) throws Exception {
        Path template = Path.of("..")
            .toAbsolutePath()
            .resolve("app/src/main/resources/templates/addressbook-delegate-created");
        var translator = new I18NTranslator.PropertiesI18NTranslator.Factory(
                template.resolve("translations").toFile())
            .forLocale(Locale.forLanguageTag(language));
        // Alice owns the source; its name and Alice's display name contain HTML-sensitive characters.
        Map<String, Object> model = Map.of(
            "translator", translator,
            "content", Map.of(
                "delegatorName", "Alice <Admin>",
                "addressBookName", "Team <Contacts>",
                "contactsUrl", CONTACTS_URL));

        String html = HtmlBodyRenderer.forPath(template.toString()).render(model);

        // The translated email links to Contacts and escapes the names in every supported language.
        assertThat(html)
            .contains(
                CONTACTS_URL,
                "cid:logo",
                "Twake Contacts Logo",
                "Alice &lt;Admin&gt;",
                "Team &lt;Contacts&gt;")
            .doesNotContain("Alice <Admin>", "Team <Contacts>");
    }
}
