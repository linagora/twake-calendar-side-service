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

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;
import com.ibm.icu.text.RelativeDateTimeFormatter;
import com.ibm.icu.util.ULocale;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class AlarmContentModelBuilder {

    public static String formatDuration(Duration duration, Locale locale) {
        long minutes = duration.toMinutes();
        RelativeDateTimeFormatter formatter = RelativeDateTimeFormatter.getInstance(ULocale.forLocale(locale));
        return formatter.format(minutes, RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.MINUTES);
    }

    public static DurationStep builder() {
        return duration -> summary -> location -> organizer -> attendees ->
            resources -> description -> link -> i18nTranslator -> () -> {
                ImmutableMap.Builder<String, Object> eventBuilder = ImmutableMap.builder();

                eventBuilder.put("summary", summary);

                location.ifPresent(loc -> eventBuilder.put("location", new LocationModel(loc).toPugModel()));

                eventBuilder.put("organizer", organizer.toPugModel());

                Map<String, Object> attendeeMap = attendees.stream()
                    .collect(Collectors.toMap(PersonModel::email, PersonModel::toPugModel));
                eventBuilder.put("attendees", attendeeMap);

                eventBuilder.put("hasResources", !resources.isEmpty());
                Map<String, Object> resourceMap = resources.stream()
                    .collect(Collectors.toMap(resource -> StringUtils.defaultIfEmpty(resource.cn(), resource.email()),
                        PersonModel::toPugModel));
                eventBuilder.put("resources", resourceMap);

                description.ifPresent(desc -> eventBuilder.put("description", desc));

                String notificationTemplate = i18nTranslator.get("notification");
                String notification = notificationTemplate.formatted(formatDuration(duration, i18nTranslator.associatedLocale()));

                return ImmutableMap.of(
                    "content", ImmutableMap.of(
                        "event", eventBuilder.build(),
                        "notification", notification,
                        "seeInCalendarLink", link));
            };
    }

    public interface DurationStep {
        SummaryStep duration(Duration duration);
    }

    public interface SummaryStep {
        LocationStep summary(String summary);
    }

    public interface LocationStep {
        OrganizerStep location(Optional<String> location);
    }

    public interface OrganizerStep {
        AttendeesStep organizer(PersonModel organizer);
    }

    public interface AttendeesStep {
        ResourcesStep attendees(List<PersonModel> attendees);
    }

    public interface ResourcesStep {
        DescriptionStep resources(List<PersonModel> resources);
    }

    public interface DescriptionStep {
        LinkStep description(Optional<String> description);
    }

    public interface LinkStep {
        TranslatorStep seeInCalendarLink(String url);
    }

    public interface TranslatorStep {
        FinalStep translator(I18NTranslator translator);
    }

    public interface FinalStep {
        Map<String, Object> buildAsMap();
    }
}
