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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class SubjectRendererTest {

    @Test
    void shouldRenderSubjectWhenNoAttributes() throws IOException {
        SubjectRenderer subjectRenderer = SubjectRenderer.of("Report import");

        assertThat(subjectRenderer.render(Map.of()))
            .isEqualTo("Report import");
    }

    @Test
    void shouldRenderSubjectWithInterpolatedVariables() throws IOException {
        SubjectRenderer subjectRenderer = SubjectRenderer.of("Report import for #{user} on #{date}");

        assertThat(subjectRenderer.render(Map.of("user", "John Doe", "date", "2023-10-01")))
            .isEqualTo("Report import for John Doe on 2023-10-01");
    }

    @Test
    void shouldRenderSubjectUsingI18NTranslator() throws IOException {
        SubjectRenderer subjectRenderer = SubjectRenderer.of("!{translator.get('accepted')}: Sprint review");

        I18NTranslator i18NTranslator = key -> {
            if ("accepted".equals(key)) {
                return "Đã chấp nhận";
            }
            return key;
        };

        Map<String, Object> scopedValue = Map.of("translator", i18NTranslator);

        assertThat(subjectRenderer.render(scopedValue))
            .isEqualTo("Đã chấp nhận: Sprint review");
    }

    @Test
    void shouldRenderSubjectWithUnicodeCharacters() throws IOException {
        SubjectRenderer subjectRenderer = SubjectRenderer.of("Báo cáo nhập sự kiện");
        assertThat(subjectRenderer.render(Map.of()))
            .isEqualTo("Báo cáo nhập sự kiện");
    }
}
