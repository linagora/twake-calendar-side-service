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

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class HtmlBookingRequestReceivedRenderTest {
    private HtmlBodyRenderer htmlBodyRenderer;
    private I18NTranslator.PropertiesI18NTranslator.Factory i18nFactory;

    @BeforeEach
    void setUp() throws Exception {
        Path templateDirectory = Paths.get(Paths.get("").toAbsolutePath().getParent().toString(),
            "app", "src", "main", "resources", "templates", "event-booking-request-received");

        htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.toAbsolutePath().toString());
        i18nFactory = new I18NTranslator.PropertiesI18NTranslator.Factory(templateDirectory.resolve("translations").toFile());
    }

    private Map<String, Object> model(Locale locale, String endDate) {
        return ImmutableMap.of(
            "content", ImmutableMap.builder()
                .put("start", ImmutableMap.of(
                    "date", "2025-06-27",
                    "fullDate", "2025-06-27",
                    "fullDateTime", "2025-06-27 10:00",
                    "time", "10:00",
                    "timezone", "Europe/Paris"))
                .put("end", ImmutableMap.of(
                    "date", endDate,
                    "fullDate", endDate,
                    "fullDateTime", endDate + " 11:00",
                    "time", "11:00"))
                .put("owner", ImmutableMap.of("cn", "Alice", "email", "alice@domain.tld"))
                .put("reservationLink", "https://calendar.domain.tld/reservation")
                .build(),
            "translator", i18nFactory.forLocale(locale));
    }

    @Test
    void renderSingleDayBookingShouldUseTimeRangeWording() {
        String result = htmlBodyRenderer.render(model(Locale.ENGLISH, "2025-06-27"));

        assertThat(result).contains("2025-06-27&nbsp;from&nbsp;10:00&nbsp;to&nbsp;11:00&nbsp;Europe/Paris");
    }

    @ParameterizedTest(name = "{index} => {1}")
    @MethodSource("localeWithMultiDayRange")
    void renderMultiDayBookingShouldUseDateRangeWording(Locale locale, String expectedRange) {
        String result = htmlBodyRenderer.render(model(locale, "2025-06-28"));

        assertThat(result).contains(expectedRange);
    }

    static Stream<Arguments> localeWithMultiDayRange() {
        return Stream.of(
            Arguments.of(Locale.ENGLISH, "2025-06-27 10:00&nbsp;to&nbsp;2025-06-28 11:00&nbsp;Europe/Paris"),
            Arguments.of(Locale.FRENCH, "2025-06-27 10:00&nbsp;au&nbsp;2025-06-28 11:00&nbsp;Europe/Paris"),
            Arguments.of(Locale.of("es"), "2025-06-27 10:00&nbsp;hasta el&nbsp;2025-06-28 11:00&nbsp;Europe/Paris"),
            Arguments.of(Locale.GERMAN, "2025-06-27 10:00&nbsp;bis&nbsp;2025-06-28 11:00&nbsp;Europe/Paris"),
            Arguments.of(Locale.ITALIAN, "2025-06-27 10:00&nbsp;fino a&nbsp;2025-06-28 11:00&nbsp;Europe/Paris")
        );
    }
}
