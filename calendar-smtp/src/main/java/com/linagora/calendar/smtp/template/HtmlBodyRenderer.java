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
import java.util.HashMap;
import java.util.Map;

import com.google.common.escape.Escaper;
import com.google.common.net.PercentEscaper;

import de.neuland.pug4j.template.FileTemplateLoader;

public class HtmlBodyRenderer {
    private static final String TEMPLATE_FILE_NAME = "html.pug";
    private static final Escaper URI_COMPONENT_ESCAPER = new PercentEscaper("-._~", false);

    public static HtmlBodyRenderer forPath(String templatePath) throws IOException {
        return new HtmlBodyRenderer(compileTemplate(templatePath, TEMPLATE_FILE_NAME));
    }

    static CompiledPugTemplate compileTemplate(String templatePath, String templateFileName) throws IOException {
        FileTemplateLoader fileLoader = new FileTemplateLoader(templatePath);
        fileLoader.setBase("");
        return CompiledPugTemplate.compile(fileLoader, templateFileName);
    }

    private final CompiledPugTemplate template;

    private HtmlBodyRenderer(CompiledPugTemplate template) {
        this.template = template;
    }

    public String render(Map<String, Object> scopedValue) {
        // Pug4j uses JEXL, so templates need an explicit URI component encoder.
        Map<String, Object> model = new HashMap<>(scopedValue);
        model.put("uriComponentEscaper", URI_COMPONENT_ESCAPER);
        return template.render(model);
    }
}
