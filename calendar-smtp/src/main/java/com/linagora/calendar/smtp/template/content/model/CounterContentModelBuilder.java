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

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.smtp.i18n.I18NTranslator;

public class CounterContentModelBuilder {
    public record NewEventModel(boolean allDay,
                                ZonedDateTime start,
                                Optional<ZonedDateTime> end,
                                Optional<String> comment) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private boolean allDay;
            private ZonedDateTime start;
            private Optional<ZonedDateTime> end = Optional.empty();
            private Optional<String> comment = Optional.empty();

            public Builder allDay(boolean allDay) {
                this.allDay = allDay;
                return this;
            }

            public Builder start(ZonedDateTime start) {
                this.start = start;
                return this;
            }

            public Builder end(Optional<ZonedDateTime> end) {
                this.end = end;
                return this;
            }

            public Builder comment(Optional<String> comment) {
                this.comment = comment;
                return this;
            }

            public NewEventModel build() {
                Objects.requireNonNull(start, "start must not be null");
                return new NewEventModel(allDay, start, end, comment);
            }
        }

        public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay) {
            ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

            builder.put("content.event.allDay", allDay);
            builder.put("content.event.start", new EventTimeModel(start).toPugModel(locale, zoneToDisplay));
            comment.ifPresent(value -> builder.put("content.event.comment", value));
            end.ifPresent(e -> builder.put("content.event.end", new EventTimeModel(e).toPugModel(locale, zoneToDisplay)));

            return builder.build();
        }
    }

    public record OldEventModel(String summary,
                                boolean allDay,
                                ZonedDateTime start,
                                Optional<ZonedDateTime> end,
                                Optional<String> location,
                                Optional<String> description,
                                List<PersonModel> attendees,
                                PersonModel organizer,
                                List<PersonModel> resources) {

        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String summary;
            private boolean allDay;
            private ZonedDateTime start;
            private Optional<ZonedDateTime> end = Optional.empty();
            private Optional<String> location = Optional.empty();
            private Optional<String> description = Optional.empty();
            private List<PersonModel> attendees;
            private PersonModel organizer;
            private List<PersonModel> resources = List.of();

            public Builder summary(String summary) {
                this.summary = summary;
                return this;
            }

            public Builder allDay(boolean allDay) {
                this.allDay = allDay;
                return this;
            }

            public Builder start(ZonedDateTime start) {
                this.start = start;
                return this;
            }

            public Builder end(Optional<ZonedDateTime> end) {
                this.end = end;
                return this;
            }

            public Builder location(Optional<String> location) {
                this.location = location;
                return this;
            }

            public Builder description(Optional<String> description) {
                this.description = description;
                return this;
            }

            public Builder attendee(List<PersonModel> attendees) {
                this.attendees = attendees;
                return this;
            }

            public Builder organizer(PersonModel organizer) {
                this.organizer = organizer;
                return this;
            }

            public Builder resources(List<PersonModel> resources) {
                this.resources = resources != null ? resources : List.of();
                return this;
            }

            public OldEventModel build() {
                Objects.requireNonNull(summary, "summary must not be null");
                Objects.requireNonNull(start, "start must not be null");
                Objects.requireNonNull(attendees, "attendee must not be null");
                Objects.requireNonNull(organizer, "organizer must not be null");
                return new OldEventModel(summary, allDay, start, end, location, description, attendees, organizer, resources);
            }
        }

        public Map<String, Object> toPugModel(Locale locale, ZoneId zoneToDisplay) {
            ImmutableMap.Builder<String, Object> builder = ImmutableMap.builder();

            builder.put("subject.summary", summary);
            builder.put("content.oldEvent", true);
            builder.put("content.oldEvent.summary", summary);
            builder.put("content.oldEvent.allDay", allDay);
            builder.put("content.oldEvent.start", new EventTimeModel(start).toPugModel(locale, zoneToDisplay));
            end.ifPresent(e -> builder.put("content.oldEvent.end", new EventTimeModel(e).toPugModel(locale, zoneToDisplay)));
            location.ifPresent(loc -> builder.put("content.oldEvent.location", new LocationModel(loc).toPugModel()));
            description.ifPresent(desc -> builder.put("content.oldEvent.description", desc));

            Map<String, Object> attendeeModel = attendees.stream()
                .collect(Collectors.toMap(PersonModel::email, PersonModel::toPugModel));
            builder.put("content.oldEvent.attendees", attendeeModel);
            builder.put("content.oldEvent.organizer", organizer.toPugModel());
            builder.put("content.oldEvent.hasResources", !resources.isEmpty());
            builder.put("content.oldEvent.resourcesJoined", resources.stream()
                .map(person -> StringUtils.defaultIfEmpty(person.cn(), person.email()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", ")));

            return builder.build();
        }
    }

    public static OldEventStep builder() {
        return oldEvent -> newEvent -> editorDisplayName ->
            locale -> zoneToDisplay -> translator -> linkFactory -> () -> {
            ImmutableMap.Builder<String, Object> mapBuilder = ImmutableMap.builder();

            mapBuilder.putAll(oldEvent.toPugModel(locale, zoneToDisplay));
            mapBuilder.putAll(newEvent.toPugModel(locale, zoneToDisplay));
            mapBuilder.put("content.editorDisplayName", editorDisplayName);
            mapBuilder.put("content.seeInCalendarLink", linkFactory.getEventInCalendarLink(newEvent.start()));

            return mapBuilder.build();
        };
    }

    public interface OldEventStep {
        NewEventStep oldEvent(OldEventModel model);
    }

    public interface NewEventStep {
        EditorDisplayNameStep newEvent(NewEventModel model);
    }

    public interface EditorDisplayNameStep {
        LocaleStep editorDisplayName(String editorDisplayName);
    }

    public interface LocaleStep {
        ZoneToDisplayStep locale(Locale locale);
    }

    public interface ZoneToDisplayStep {
        TranslatorStep zoneToDisplay(ZoneId zoneId);
    }

    public interface TranslatorStep {
        LinkStep translator(I18NTranslator translator);
    }

    public interface LinkStep {
        FinalStep eventInCalendarLink(EventInCalendarLinkFactory linkFactory);
    }

    public interface FinalStep {
        Map<String, Object> buildAsMap();
    }
}
