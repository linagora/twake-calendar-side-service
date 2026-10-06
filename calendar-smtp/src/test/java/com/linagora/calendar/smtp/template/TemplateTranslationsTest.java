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

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class TemplateTranslationsTest {
    private static final List<String> LANGUAGES = List.of("en", "fr", "ru", "vi", "es", "de", "it");

    static Stream<Path> templateDirectories() throws IOException {
        Path templatesDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");
        try (Stream<Path> paths = Files.list(templatesDirectory)) {
            return paths.filter(Files::isDirectory)
                .sorted()
                .toList()
                .stream();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("templateDirectories")
    void everyLanguageShouldTranslateEveryEnglishKey(Path templateDirectory) throws IOException {
        Set<String> englishKeys = loadKeys(templateDirectory, "en");

        for (String language : LANGUAGES) {
            assertThat(loadKeys(templateDirectory, language))
                .as("keys of messages_%s.properties in %s", language, templateDirectory.getFileName())
                .containsAll(englishKeys);
        }
    }

    private Set<String> loadKeys(Path templateDirectory, String language) throws IOException {
        Path file = templateDirectory.resolve("translations").resolve("messages_" + language + ".properties");
        assertThat(file).exists();

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties.stringPropertyNames();
    }
}
