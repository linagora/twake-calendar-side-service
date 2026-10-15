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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import de.neuland.pug4j.Pug4J;
import de.neuland.pug4j.template.PugTemplate;

/**
 * Renders the text/plain alternative of a mail. The template is optional so that
 * custom template directories lacking a text version keep working (HTML only).
 */
public class TextBodyRenderer {
    public static final String TEMPLATE_FILE_NAME = "text.pug";

    public static Optional<TextBodyRenderer> forPath(String templatePath) throws IOException {
        if (!Files.isRegularFile(Path.of(templatePath, TEMPLATE_FILE_NAME))) {
            return Optional.empty();
        }
        return Optional.of(new TextBodyRenderer(HtmlBodyRenderer.loadTemplate(templatePath, TEMPLATE_FILE_NAME)));
    }

    private final PugTemplate template;

    public TextBodyRenderer(PugTemplate template) {
        this.template = template;
    }

    public String render(Map<String, Object> scopedValue) {
        return Pug4J.render(template, scopedValue);
    }
}
