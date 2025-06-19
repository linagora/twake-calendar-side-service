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
import java.util.Map;

import de.neuland.pug4j.Pug4J;
import de.neuland.pug4j.PugConfiguration;
import de.neuland.pug4j.template.FileTemplateLoader;
import de.neuland.pug4j.template.PugTemplate;

public class HtmlBodyRenderer {
    public static HtmlBodyRenderer forPath(String templatePath) throws IOException {
        FileTemplateLoader fileLoader = new FileTemplateLoader(templatePath);
        fileLoader.setBase("");

        PugConfiguration pugConfiguration = new PugConfiguration();
        pugConfiguration.setTemplateLoader(fileLoader);

        return new HtmlBodyRenderer(pugConfiguration.getTemplate(templatePath + "html.pug"));
    }

    private final PugTemplate template;

    public HtmlBodyRenderer(PugTemplate template) {
        this.template = template;
    }

    public String render(Map<String, Object> scopedValue) {
        return Pug4J.render(template, scopedValue);
    }
}
