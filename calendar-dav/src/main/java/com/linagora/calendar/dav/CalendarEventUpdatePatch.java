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

package com.linagora.calendar.dav;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.RelationshipPropertyModifiers;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.PartStat;

public interface CalendarEventUpdatePatch {

    /**
     * @return true if the patch was applied; false otherwise
     */
    boolean apply(VEvent vEvent);

    class AttendeePartStatusUpdatePatch implements CalendarEventUpdatePatch {

        private final String targetAttendeeEmail;
        private final PartStat status;

        public AttendeePartStatusUpdatePatch(Username username, PartStat status) {
            this(username.asString(), status);
        }

        public AttendeePartStatusUpdatePatch(String targetAttendeeEmail, PartStat status) {
            this.targetAttendeeEmail = Preconditions.checkNotNull(targetAttendeeEmail, "targetAttendeeEmail must not be null");
            this.status = Preconditions.checkNotNull(status, "status must not be null");
        }

        @Override
        public boolean apply(VEvent vEvent) {
            return vEvent.getAttendees()
                .stream()
                .filter(attendee -> StringUtils.equalsIgnoreCase(
                    attendee.getCalAddress().toASCIIString(), "mailto:" + targetAttendeeEmail))
                .findFirst()
                .map(attendee -> {
                    Optional<Parameter> currentStatusOpt = attendee.getParameter(Parameter.PARTSTAT);
                    boolean sameStatus = currentStatusOpt
                        .map(Content::getValue)
                        .map(currentValue -> currentValue.equals(status.getValue()))
                        .orElse(false);

                    if (sameStatus) {
                        return false;
                    } else {
                        vEvent.with(RelationshipPropertyModifiers.ATTENDEE, attendee.replace(status));
                        return true;
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException("The attendee is not found in the event. " + targetAttendeeEmail));
        }
    }

    static void addProperties(VEvent vEvent, Property... properties) {
        List<Property> newProperties = ImmutableList.<Property>builder()
            .addAll(vEvent.getProperties())
            .addAll(Arrays.asList(properties))
            .build();

        vEvent.setPropertyList(new PropertyList(newProperties));
    }

    static void removeProperties(VEvent vEvent, String... propertyNames) {
        Set<String> namesToRemove = Arrays.stream(propertyNames)
            .collect(Collectors.toSet());

        List<Property> filtered = vEvent.getProperties().stream()
            .filter(p -> !namesToRemove.contains(p.getName()))
            .toList();

        vEvent.setPropertyList(new PropertyList(filtered));
    }
}
