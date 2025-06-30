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
import java.io.StringReader;
import java.util.Map;

import de.neuland.pug4j.Pug4J;
import de.neuland.pug4j.PugConfiguration;
import de.neuland.pug4j.template.PugTemplate;
import de.neuland.pug4j.template.ReaderTemplateLoader;

public class SubjectRenderer {
    private static final String TEMPLATE_LOADER_NAME = "inline";
    private static final String AVOID_HTML_TAG_CHARACTER = "|";

    public static SubjectRenderer of(String templateString) throws IOException {
        String safeTemplate = AVOID_HTML_TAG_CHARACTER + templateString;

        ReaderTemplateLoader loader = new ReaderTemplateLoader(new StringReader(safeTemplate), TEMPLATE_LOADER_NAME);

        PugConfiguration pugConfiguration = new PugConfiguration();
        pugConfiguration.setTemplateLoader(loader);
        return new SubjectRenderer(pugConfiguration.getTemplate(TEMPLATE_LOADER_NAME));
    }

    private final PugTemplate template;

    private SubjectRenderer(PugTemplate template) {
        this.template = template;
    }

    public String render(Map<String, Object> scopedValue) {
        return Pug4J.render(template, scopedValue);
    }
}
