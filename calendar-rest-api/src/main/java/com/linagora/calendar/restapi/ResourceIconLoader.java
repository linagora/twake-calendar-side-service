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

package com.linagora.calendar.restapi;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Named;

import org.apache.commons.lang3.Strings;
import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

public class ResourceIconLoader {
    public static final String RESOURCES_ICONS_KEY = "resources-icons";
    private static final String EXT_SVG = ".svg";

    public static AbstractModule MODULE =  new AbstractModule() {

        @Provides
        @Named(RESOURCES_ICONS_KEY)
        Map<String, byte[]> resourcesIcons() {
            URL dirURL = ClassLoader.getSystemResource("icons/resources");
            return ResourceIconLoader.loadFromDir(dirURL);
        }
    };

    public static Map<String, byte[]> loadFromDir(URL dirURL) {
        Objects.requireNonNull(dirURL, "Directory URL must not be null");

        try {
            File folder = new File(dirURL.toURI());
            File[] files = folder.listFiles((dir, name) -> Strings.CI.endsWith(name, EXT_SVG));

            if (files == null || files.length == 0) {
                return Map.of();
            }

            ImmutableMap.Builder<@NotNull String, byte @NotNull []> builder = ImmutableMap.builder();
            for (File file : files) {
                String iconName = Strings.CI.removeEnd(file.getName(), EXT_SVG);
                byte[] content = Files.readAllBytes(file.toPath());
                builder.put(iconName, content);
            }
            return builder.build();

        } catch (URISyntaxException | IOException e) {
            throw new RuntimeException("Error loading resources from: " + dirURL, e);
        }
    }
}
