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

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;

import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.linagora.calendar.storage.event.EventParseUtils;

import net.fortuna.ical4j.model.Content;
import net.fortuna.ical4j.model.Parameter;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.parameter.Cn;
import net.fortuna.ical4j.model.parameter.CuType;
import net.fortuna.ical4j.model.parameter.PartStat;
import net.fortuna.ical4j.model.parameter.Role;
import net.fortuna.ical4j.model.parameter.Rsvp;
import net.fortuna.ical4j.model.property.Attendee;

public interface CalendarEventUpdatePatch {

    /**
     * @return true if the patch was applied; false otherwise
     */
    boolean apply(VEvent vEvent);

    /**
     * A calendar object holds a VEvent per changed occurrence of a recurring event (RECURRENCE-ID) besides the
     * master one: the patch is applied to each VEvent it targets, so that a change of the series reaches all of them.
     *
     * @return true if the patch is to be applied to this VEvent of the calendar object
     */
    default boolean targets(VEvent vEvent) {
        return true;
    }

    class AttendeePartStatusUpdatePatch implements CalendarEventUpdatePatch {

        private final String targetAttendeeEmail;
        private final PartStat status;
        private final Optional<String> recurrenceId;

        public AttendeePartStatusUpdatePatch(Username username, PartStat status) {
            this(username, status, Optional.empty());
        }

        public AttendeePartStatusUpdatePatch(Username username, PartStat status, Optional<String> recurrenceId) {
            this(username.asString(), status, recurrenceId);
        }

        public AttendeePartStatusUpdatePatch(String targetAttendeeEmail, PartStat status) {
            this(targetAttendeeEmail, status, Optional.empty());
        }

        public AttendeePartStatusUpdatePatch(String targetAttendeeEmail, PartStat status, Optional<String> recurrenceId) {
            this.targetAttendeeEmail = Preconditions.checkNotNull(targetAttendeeEmail, "targetAttendeeEmail must not be null");
            this.status = Preconditions.checkNotNull(status, "status must not be null");
            this.recurrenceId = Preconditions.checkNotNull(recurrenceId, "recurrenceId must not be null");
        }

        /**
         * Without RECURRENCE-ID, the answer of an attendee is for the whole series: every VEvent the attendee is
         * invited to is targeted. With a RECURRENCE-ID, the answer is for that occurrence only.
         */
        @Override
        public boolean targets(VEvent vEvent) {
            return targetAttendee(vEvent).isPresent() && targetsOccurrence(vEvent);
        }

        private boolean targetsOccurrence(VEvent vEvent) {
            return recurrenceId
                .map(expected -> EventParseUtils.getRecurrenceId(vEvent)
                    .filter(actual -> Strings.CI.equals(actual, expected))
                    .isPresent())
                .orElse(true);
        }

        @Override
        public boolean apply(VEvent vEvent) {
            return targetAttendee(vEvent)
                .map(attendee -> {
                    Optional<Parameter> currentStatusOpt = attendee.getParameter(Parameter.PARTSTAT);
                    boolean sameStatus = currentStatusOpt
                        .map(Content::getValue)
                        .map(currentValue -> currentValue.equals(status.getValue()))
                        .orElse(false);

                    if (sameStatus) {
                        return false;
                    } else {
                        replaceProperty(vEvent, attendee, attendee.replace(status));
                        return true;
                    }
                })
                .orElseThrow(() -> new IllegalArgumentException("The attendee is not found in the event. " + targetAttendeeEmail));
        }

        private Optional<Attendee> targetAttendee(VEvent vEvent) {
            return vEvent.getAttendees()
                .stream()
                .filter(attendee -> Strings.CI.equals(
                    attendee.getCalAddress().toASCIIString(), "mailto:" + targetAttendeeEmail))
                .findFirst();
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                .add("targetAttendeeEmail", targetAttendeeEmail)
                .add("status", status.getValue())
                .add("recurrenceId", recurrenceId)
                .toString();
        }
    }

    /**
     * Invites an attendee to every VEvent of the calendar object, so that a recurring event and all its changed
     * occurrences are concerned. An attendee already invited is left untouched.
     */
    record AddAttendeePatch(MailAddress email, Optional<String> displayName, PartStat partStat) implements CalendarEventUpdatePatch {
        private static final String MAIL_TO_PREFIX = "mailto:";

        public AddAttendeePatch {
            Preconditions.checkNotNull(email, "email must not be null");
            Preconditions.checkNotNull(displayName, "displayName must not be null");
            Preconditions.checkNotNull(partStat, "partStat must not be null");
        }

        @Override
        public boolean apply(VEvent vEvent) {
            boolean alreadyInvited = vEvent.getAttendees().stream()
                .anyMatch(attendee -> Strings.CI.equals(attendee.getCalAddress().toASCIIString(), MAIL_TO_PREFIX + email.asString()));
            if (alreadyInvited) {
                return false;
            }
            addProperties(vEvent, attendee());
            return true;
        }

        private Attendee attendee() {
            Attendee attendee = (Attendee) new Attendee(URI.create(MAIL_TO_PREFIX + email.asString()))
                .withParameter(Rsvp.TRUE)
                .withParameter(Role.REQ_PARTICIPANT)
                .withParameter(CuType.INDIVIDUAL)
                .withParameter(partStat)
                .getFluentTarget();
            displayName.map(Cn::new).ifPresent(attendee::withParameter);
            return attendee;
        }
    }

    static void addProperties(VEvent vEvent, Property... properties) {
        List<Property> newProperties = ImmutableList.<Property>builder()
            .addAll(vEvent.getProperties())
            .addAll(Arrays.asList(properties))
            .build();

        vEvent.setPropertyList(new PropertyList(newProperties));
    }

    /**
     * Replaces only the given property instance (by identity), leaving other properties
     * sharing the same value (e.g. duplicated ATTENDEE addresses) untouched.
     */
    static void replaceProperty(VEvent vEvent, Property oldProperty, Property newProperty) {
        List<Property> newProperties = vEvent.getProperties()
            .stream()
            .map(property -> property == oldProperty ? newProperty : property)
            .toList();

        vEvent.setPropertyList(new PropertyList(newProperties));
    }

}
