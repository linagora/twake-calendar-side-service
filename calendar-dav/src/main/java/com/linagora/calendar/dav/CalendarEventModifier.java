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

import static com.linagora.calendar.dav.CalendarEventUpdatePatch.addProperties;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;

import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.validate.ValidationResult;

public class CalendarEventModifier {
    private static final Sequence MODIFIED_SEQUENCE_DEFAULT = new Sequence("1");

    public static class NoUpdateRequiredException extends RuntimeException {
        public NoUpdateRequiredException() {
            super("No update was required on the calendar event");
        }
    }

    public static CalendarEventModifier of(CalendarEventUpdatePatch... patches) {
        List<CalendarEventUpdatePatch> patchList = List.of(patches);
        return new CalendarEventModifier(patchList, Optional.empty());
    }

    private final List<CalendarEventUpdatePatch> patches;
    private final Optional<Consumer<Calendar>> validator;

    public CalendarEventModifier(List<CalendarEventUpdatePatch> patches,
                                 Optional<Consumer<Calendar>> validator) {
        this.patches = Preconditions.checkNotNull(patches);
        this.validator = validator;
    }

    public Calendar apply(Calendar calendar) {
        Calendar newCalendar = calendar.copy();
        validator.ifPresent(v -> v.accept(newCalendar));

        VEvent vEventToUpdate = firstVEvent(newCalendar);

        boolean modified = patches.stream().anyMatch(patch -> patch.apply(vEventToUpdate));
        if (!modified) {
            throw new NoUpdateRequiredException();
        }

        markEventAsModified(vEventToUpdate);
        validateAfterUpdate(newCalendar);

        return newCalendar;
    }

    public static void markEventAsModified(VEvent vEvent) {
        Optional.ofNullable(vEvent.getSequence())
            .ifPresentOrElse(sequence -> sequence.setValue(String.valueOf(sequence.getSequenceNo() + 1)),
                () -> addProperties(vEvent, MODIFIED_SEQUENCE_DEFAULT));

        Optional.ofNullable(vEvent.getDateTimeStamp())
            .ifPresent(dtStamp -> dtStamp.setDate(Instant.now()));
    }

    public static void validateAfterUpdate(Calendar calendar) {
        ValidationResult result = calendar.validate();

        Preconditions.checkArgument(!result.hasErrors(),
            "Invalid calendar event: " + result.getEntries().stream()
                .map(entry -> entry.getContext() + " : " + entry.getMessage())
                .collect(Collectors.joining("; ")));
    }

    private VEvent firstVEvent(Calendar calendar) {
        return calendar.getComponents().stream()
            .filter(component -> component instanceof VEvent)
            .map(component -> (VEvent) component)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No VEvent found in the calendar"));
    }
}
