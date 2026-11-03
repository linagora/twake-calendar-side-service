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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.FileNotFoundException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import org.apache.james.core.MaybeSender;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;

class MessageGeneratorFactoryTest {
    private static final TemplateType IMPORT_CALENDAR = new TemplateType("import-calendar");
    private static final TemplateType IMPORT_CONTACTS = new TemplateType("import-contacts");

    private MessageGenerator.Factory.Cached factory;

    @BeforeEach
    void setUp() {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates");
        MailTemplateConfiguration configuration = new MailTemplateConfiguration("file://" + templateDirectory.toAbsolutePath(),
            MaybeSender.getMailSender("no-reply@twake.app"));
        factory = (MessageGenerator.Factory.Cached) MessageGenerator.factory(configuration, FileSystemImpl.forTesting(), new MemoryOpenPaaSUserDAO())
            .cached();
    }

    @Test
    void cachedFactoryShouldNotGrowWithEachDistinctLanguage() throws Exception {
        for (int i = 0; i < 1000; i++) {
            factory.forLocalizedFeature(new Language(Locale.of("zz" + i)), IMPORT_CALENDAR);
        }

        assertThat(factory.cacheSize()).isEqualTo(1);
    }

    @Test
    void cachedFactoryShouldShareTheEnglishGeneratorForUnknownLanguages() throws Exception {
        MessageGenerator english = factory.forLocalizedFeature(new Language(Locale.ENGLISH), IMPORT_CALENDAR);

        assertThat(factory.forLocalizedFeature(new Language(Locale.of("zz-cache-0001")), IMPORT_CALENDAR))
            .isSameAs(english);
    }

    @Test
    void cachedFactoryShouldKeepOneGeneratorPerTranslation() throws Exception {
        MessageGenerator french = factory.forLocalizedFeature(new Language(Locale.FRENCH), IMPORT_CALENDAR);
        MessageGenerator english = factory.forLocalizedFeature(new Language(Locale.ENGLISH), IMPORT_CALENDAR);
        factory.forLocalizedFeature(new Language(Locale.FRENCH), IMPORT_CONTACTS);

        assertThat(french).isNotSameAs(english);
        assertThat(factory.forLocalizedFeature(new Language(Locale.FRENCH), IMPORT_CALENDAR)).isSameAs(french);
        assertThat(factory.cacheSize()).isEqualTo(3);
    }

    @Test
    void cachedFactoryShouldThrowWhenTemplateIsMissing() {
        assertThatThrownBy(() -> factory.forLocalizedFeature(new Language(Locale.ENGLISH), new TemplateType("missing")))
            .isInstanceOf(FileNotFoundException.class);
    }
}
