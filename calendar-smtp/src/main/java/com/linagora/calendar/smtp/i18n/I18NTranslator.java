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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

public interface I18NTranslator {
    String get(String key);

    class PropertiesI18NTranslator implements I18NTranslator {
        private static final Logger LOGGER = LoggerFactory.getLogger(PropertiesI18NTranslator.class);

        private final ResourceBundle resourceBundle;
        private final Optional<PropertiesI18NTranslator> fallbackTranslator;

        private PropertiesI18NTranslator(ResourceBundle resourceBundle) {
            this.resourceBundle = resourceBundle;
            this.fallbackTranslator = Optional.empty();
        }

        private PropertiesI18NTranslator(ResourceBundle resourceBundle, PropertiesI18NTranslator fallbackTranslator) {
            this.resourceBundle = resourceBundle;
            this.fallbackTranslator = Optional.of(fallbackTranslator);
        }

        @Override
        public String get(String key) {
            if (resourceBundle.containsKey(key)) {
                return resourceBundle.getString(key);
            }
            LOGGER.debug("Key '{}' not found in locale '{}', trying fallback", key, resourceBundle.getLocale().toLanguageTag());
            // If the key is not found in the current locale, try the fallback translator
            return fallbackTranslator.map(translator -> translator.get(key))
                .orElse(key);
        }

        public static class Factory {
            private static final Logger LOGGER = LoggerFactory.getLogger(Factory.class);
            private static final Locale DEFAULT_LOCALE = Locale.ENGLISH;
            private static final String BUNDLE_BASE_NAME = "messages";
            private final ResourceBundle.Control control;
            private final File baseDirectory;

            // Cache: Locale → Translator
            private final LoadingCache<Locale, PropertiesI18NTranslator> cache;

            public Factory(File baseDirectory) {
                this.baseDirectory = baseDirectory;
                this.control = new FileResourceBundleControl(baseDirectory);

                PropertiesI18NTranslator defaultTranslator = createDefaultTranslator();

                this.cache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .build(locale -> {
                        LOGGER.debug("Loading translation for locale: {}", locale);
                        if (DEFAULT_LOCALE.equals(locale)) {
                            return defaultTranslator;
                        } else {
                            try {
                                return new PropertiesI18NTranslator(loadResourceBundle(locale), defaultTranslator);
                            } catch (MissingResourceException e) {
                                LOGGER.warn("Missing translate file for locale '{}' at {}, falling back to default", locale.toLanguageTag(), translateAbsolutePath(locale));
                                return defaultTranslator;
                            }
                        }
                    });
            }

            public I18NTranslator forLocale(Locale locale) {
                return cache.get(locale);
            }

            private PropertiesI18NTranslator createDefaultTranslator() {
                try {
                    return new PropertiesI18NTranslator(loadResourceBundle(DEFAULT_LOCALE));
                } catch (MissingResourceException e) {
                    throw new IllegalStateException("Missing required translation file: %s".formatted(translateAbsolutePath(DEFAULT_LOCALE)));
                }
            }

            private ResourceBundle loadResourceBundle(Locale locale) {
                return ResourceBundle.getBundle(BUNDLE_BASE_NAME, locale, control);
            }

            private String translateAbsolutePath(Locale locale) {
                return Paths.get(baseDirectory.getAbsolutePath(), BUNDLE_BASE_NAME + "_" + locale.toLanguageTag() + ".properties").toString();
            }
        }

        private static class FileResourceBundleControl extends ResourceBundle.Control {
            private final File baseDirectory;

            public FileResourceBundleControl(File baseDirectory) {
                this.baseDirectory = baseDirectory;
            }

            @Override
            public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader, boolean reload) throws IOException {
                String bundleName = toBundleName(baseName, locale);
                File file = new File(baseDirectory, bundleName + ".properties");

                if (!file.exists()) {
                    throw new FileNotFoundException("File not found: " + file.getAbsolutePath());
                }

                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    return new PropertyResourceBundle(reader);
                }
            }

            @Override
            public long getTimeToLive(String baseName, Locale locale) {
                return TTL_DONT_CACHE;
            }
        }
    }
}