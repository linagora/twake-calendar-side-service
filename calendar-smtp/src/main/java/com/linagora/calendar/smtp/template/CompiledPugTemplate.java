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

import de.neuland.pug4j.PugConfiguration;
import de.neuland.pug4j.template.PugTemplate;
import de.neuland.pug4j.template.TemplateLoader;

/**
 * A pug template together with the configuration that compiled it. Rendering with that configuration reuses its JEXL
 * engine, hence its introspection cache, whereas {@code Pug4J.render} builds a new configuration, thus a new engine,
 * at each call.
 */
record CompiledPugTemplate(PugConfiguration configuration, PugTemplate template) {
    static CompiledPugTemplate compile(TemplateLoader templateLoader, String templateName) throws IOException {
        PugConfiguration configuration = new PugConfiguration();
        configuration.setTemplateLoader(templateLoader);
        return new CompiledPugTemplate(configuration, configuration.getTemplate(templateName));
    }

    String render(Map<String, Object> model) {
        return configuration.renderTemplate(template, model);
    }
}
