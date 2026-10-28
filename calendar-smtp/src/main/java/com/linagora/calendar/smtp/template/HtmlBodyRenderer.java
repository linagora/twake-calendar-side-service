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

import de.neuland.pug4j.Pug4J;
import de.neuland.pug4j.PugConfiguration;
import de.neuland.pug4j.template.FileTemplateLoader;
import de.neuland.pug4j.template.PugTemplate;

public class HtmlBodyRenderer {
    private static final String TEMPLATE_FILE_NAME = "html.pug";
    private static final Escaper URI_COMPONENT_ESCAPER = new PercentEscaper("-._~", false);

    public static HtmlBodyRenderer forPath(String templatePath) throws IOException {
        return new HtmlBodyRenderer(loadTemplate(templatePath, TEMPLATE_FILE_NAME));
    }

    static PugTemplate loadTemplate(String templatePath, String templateFileName) throws IOException {
        FileTemplateLoader fileLoader = new FileTemplateLoader(templatePath);
        fileLoader.setBase("");

        PugConfiguration pugConfiguration = new PugConfiguration();
        pugConfiguration.setTemplateLoader(fileLoader);

        return pugConfiguration.getTemplate(templateFileName);
    }

    private final PugTemplate template;

    public HtmlBodyRenderer(PugTemplate template) {
        this.template = template;
    }

    public String render(Map<String, Object> scopedValue) {
        // Pug4j uses JEXL, so templates need an explicit URI component encoder.
        Map<String, Object> model = new HashMap<>(scopedValue);
        model.put("uriComponentEscaper", URI_COMPONENT_ESCAPER);
        return Pug4J.render(template, model);
    }
}
