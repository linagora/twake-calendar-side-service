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
import de.neuland.pug4j.expression.JexlExpressionHandler;
import de.neuland.pug4j.expression.JexlExpressionHandlerOptions;
import de.neuland.pug4j.template.PugTemplate;
import de.neuland.pug4j.template.TemplateLoader;

/**
 * A pug template together with the configuration that compiled it. Rendering with that configuration reuses its JEXL
 * engine, hence its introspection cache and its parsed expressions, whereas {@code Pug4J.render} builds a new
 * configuration, thus a new engine, at each call.
 */
record CompiledPugTemplate(PugConfiguration configuration, PugTemplate template) {
    /**
     * JEXL only caches the parsed expressions shorter than its threshold, 64 characters by default: the longer ones of
     * the templates were parsed again at every render. The expressions are the fixed text of the templates, not data,
     * so caching them all is bounded by the templates.
     */
    private static final int EXPRESSION_CACHE_THRESHOLD = 4096;

    static CompiledPugTemplate compile(TemplateLoader templateLoader, String templateName) throws IOException {
        PugConfiguration configuration = new PugConfiguration();
        configuration.setExpressionHandler(expressionHandler());
        configuration.setTemplateLoader(templateLoader);
        return new CompiledPugTemplate(configuration, configuration.getTemplate(templateName));
    }

    private static JexlExpressionHandler expressionHandler() {
        JexlExpressionHandlerOptions options = new JexlExpressionHandlerOptions();
        options.setCacheThreshold(EXPRESSION_CACHE_THRESHOLD);
        return new JexlExpressionHandler(options);
    }

    String render(Map<String, Object> model) {
        return configuration.renderTemplate(template, model);
    }
}
