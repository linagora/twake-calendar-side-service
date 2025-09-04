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

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Named;

import org.jetbrains.annotations.NotNull;

import com.google.common.collect.ImmutableMap;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

import io.github.classgraph.ClassGraph;
import io.github.classgraph.Resource;
import io.github.classgraph.ScanResult;

public class ResourceIconLoader {
    public static final String RESOURCES_ICONS_KEY = "resources-icons";
    private static final String EXT_SVG = ".svg";

    public static AbstractModule MODULE = new AbstractModule() {

        @Provides
        @Named(RESOURCES_ICONS_KEY)
        Map<String, byte[]> resourcesIcons() {
            return ResourceIconLoader.loadFromDir("icons/resources");
        }
    };

    public static Map<String, byte[]> loadFromDir(String resourcePath) {
        Objects.requireNonNull(resourcePath, "Directory URL must not be null");

        try (ScanResult scanResult = new ClassGraph()
            .acceptPaths(resourcePath)
            .scan()) {
            ImmutableMap.Builder<@NotNull String, byte @NotNull []> builder = ImmutableMap.builder();
            for (Resource resource : scanResult.getAllResources()) {
                if (resource.getPath().endsWith(EXT_SVG)) {
                    String fileName = resource.getPath()
                        .substring(resourcePath.length() + 1, resource.getPath().length() - EXT_SVG.length());
                    builder.put(fileName, resource.load());
                }
            }
            return builder.build();
        } catch (IOException e) {
            throw new RuntimeException("Error loading resources from: " + resourcePath, e);
        }
    }
}
