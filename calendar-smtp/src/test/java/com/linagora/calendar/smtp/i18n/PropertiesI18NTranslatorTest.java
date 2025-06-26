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

package com.linagora.calendar.smtp.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Joiner;
import com.google.common.io.Files;
import com.linagora.calendar.smtp.i18n.I18NTranslator.PropertiesI18NTranslator;

public class PropertiesI18NTranslatorTest {

    @TempDir
    File tempDir;

    private PropertiesI18NTranslator.Factory translatorFactory() {
        return new PropertiesI18NTranslator.Factory(tempDir);
    }

    @Test
    void shouldReturnTranslatedValueForExistingKey() {
        writePropertiesFile("messages_en.properties", Map.of(
            "greeting", "Hello",
            "farewell", "Goodbye"));
        I18NTranslator translator = translatorFactory().forLocale(Locale.ENGLISH);
        assertThat(translator.get("greeting")).isEqualTo("Hello");
    }

    @Test
    void shouldThrowExceptionWhenDefaultLocaleFileIsMissing() {
        assertThatThrownBy(() -> new PropertiesI18NTranslator.Factory(tempDir))
            .isInstanceOf(Exception.class)
            .hasMessageContaining("Missing required translation file")
            .hasMessageContaining("messages_en.properties");
    }

    @Test
    void shouldPreferKeyFromRequestedLocaleEvenIfFallbackHasSameKey() {
        writePropertiesFile("messages_en.properties", Map.of(
            "greeting", "Hello"));

        writePropertiesFile("messages_fr.properties", Map.of(
            "greeting", "Bonjour"));

        I18NTranslator translator = translatorFactory().forLocale(Locale.FRENCH);
        String result = translator.get("greeting");

        assertThat(result).isEqualTo("Bonjour");

        // Ensure that the English translation is correctly loaded
        assertThat(translatorFactory().forLocale(Locale.ENGLISH).get("greeting")).isEqualTo("Hello");
    }

    @Test
    void shouldFallbackToDefaultLocaleWhenKeyMissingInSpecificLocale() {
        writePropertiesFile("messages_en.properties", Map.of(
            "greeting", "Hello",
            "farewell", "Goodbye"));

        writePropertiesFile("messages_vi.properties", Map.of(
            "greeting", "Xin chào"));

        I18NTranslator translator = translatorFactory().forLocale(Locale.of("vi"));
        String result = translator.get("farewell");

        assertThat(result).isEqualTo("Goodbye"); // fallback to en
    }

    @Test
    void shouldFallbackToDefaultLocaleWhenLocaleFileIsMissing() {
        writePropertiesFile("messages_en.properties", Map.of(
            "greeting", "Hello",
            "farewell", "Goodbye"
        ));

        I18NTranslator translator = translatorFactory().forLocale(Locale.FRANCE);
        String result = translator.get("greeting");

        assertThat(result).isEqualTo("Hello");
    }

    @Test
    void shouldReturnKeyWhenNotFoundInLocaleOrFallback() {
        writePropertiesFile("messages_en.properties", Map.of());

        I18NTranslator translator = translatorFactory().forLocale(Locale.FRANCE);
        assertThat(translator.get("some_missing_key")).isEqualTo("some_missing_key");
    }

    @Test
    void shouldReturnSameTranslatorInstanceCache() {
        writePropertiesFile("messages_en.properties", Map.of("a", "A"));

        PropertiesI18NTranslator.Factory factory = translatorFactory();
        I18NTranslator translator1 = factory.forLocale(Locale.ENGLISH);
        I18NTranslator translator2 = factory.forLocale(Locale.ENGLISH);

        assertThat(translator1).isSameAs(translator2);
    }

    @Test
    void shouldSupportUnicodeCharacters() {
        writePropertiesFile("messages_en.properties", Map.of());
        writePropertiesFile("messages_vi.properties", Map.of(
            "emoji", "😊",
            "vietnamese", "Chào bạn"
        ));

        I18NTranslator translator = translatorFactory().forLocale(Locale.of("vi"));
        assertThat(translator.get("emoji")).isEqualTo("😊");
        assertThat(translator.get("vietnamese")).isEqualTo("Chào bạn");
    }

    @Test
    void shouldReturnAllKeysInBulkTest() {
        writePropertiesFile("messages_en.properties", Map.of(
            "key1", "value1",
            "key2", "value2",
            "key3", "value3"
        ));

        var translator = translatorFactory().forLocale(Locale.ENGLISH);
        assertThat(translator.get("key1")).isEqualTo("value1");
        assertThat(translator.get("key2")).isEqualTo("value2");
        assertThat(translator.get("key3")).isEqualTo("value3");
    }

    private void writePropertiesFile(String fileName, Map<String, String> keyValues) {
        File file = new File(tempDir, fileName);
        String content = Joiner.on(System.lineSeparator())
            .withKeyValueSeparator("=")
            .join(keyValues);
        Throwing.runnable(() -> Files.asCharSink(file, StandardCharsets.UTF_8).write(content)).run();
    }
}
