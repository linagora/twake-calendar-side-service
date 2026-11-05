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

package com.linagora.calendar.smtp.template.content.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class AlarmContentModelBuilderTest {

    @Test
    void shouldKeepResourcesSharingTheSameCn() {
        assertThat(resourcesOf(new PersonModel("Room", "room1@abc.com"), new PersonModel("Room", "room2@abc.com")))
            .hasSize(2);
    }

    @Test
    void shouldKeepAResourceWithoutCnWhoseEmailEqualsTheCnOfAnother() {
        assertThat(resourcesOf(new PersonModel("", "room@abc.com"), new PersonModel("room@abc.com", "other@abc.com")))
            .hasSize(2);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> resourcesOf(PersonModel... resources) {
        Map<String, Object> model = AlarmContentModelBuilder.builder()
            .duration(Duration.ofMinutes(10))
            .summary("Meeting with rooms")
            .location(Optional.empty())
            .organizer(PersonModel.EMPTY)
            .attendees(List.of())
            .resources(List.of(resources))
            .description(Optional.empty())
            .videoconference(Optional.empty())
            .locale(Locale.ENGLISH)
            .buildAsMap();

        Map<String, Object> content = (Map<String, Object>) model.get("content");
        Map<String, Object> event = (Map<String, Object>) content.get("event");
        return (Map<String, Object>) event.get("resources");
    }
}
