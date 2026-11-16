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

import java.time.DateTimeException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.base.Preconditions;
import com.linagora.calendar.api.CalendarUtil;

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
        Calendar newCalendar = rejectingUnparsableDates(() -> CalendarUtil.copyOf(calendar));
        validator.ifPresent(v -> v.accept(newCalendar));

        List<VEvent> vEvents = vEvents(newCalendar);
        patches.forEach(patch -> requireTargetedVEvent(patch, vEvents));

        List<VEvent> modifiedVEvents = vEvents.stream()
            .filter(this::applyPatches)
            .toList();
        if (modifiedVEvents.isEmpty()) {
            throw new NoUpdateRequiredException();
        }

        modifiedVEvents.forEach(CalendarEventModifier::markEventAsModified);
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
        ValidationResult result = validate(calendar);

        Preconditions.checkArgument(!result.hasErrors(),
            "Invalid calendar event: " + result.getEntries().stream()
                .map(entry -> entry.getContext() + " : " + entry.getMessage())
                .collect(Collectors.joining("; ")));
    }

    private static ValidationResult validate(Calendar calendar) {
        return rejectingUnparsableDates(calendar::validate);
    }

    // ical4j lazily parses date values kept by relaxed parsing: copy and validation throw on unparsable ones
    private static <T> T rejectingUnparsableDates(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalArgumentException("Invalid calendar event: " + e.getMessage(), e);
        }
    }

    private static List<VEvent> vEvents(Calendar calendar) {
        List<VEvent> vEvents = calendar.getComponents().stream()
            .filter(component -> component instanceof VEvent)
            .map(component -> (VEvent) component)
            .toList();
        if (vEvents.isEmpty()) {
            throw new IllegalStateException("No VEvent found in the calendar");
        }
        return vEvents;
    }

    private static void requireTargetedVEvent(CalendarEventUpdatePatch patch, List<VEvent> vEvents) {
        if (vEvents.stream().noneMatch(patch::targets)) {
            throw new IllegalArgumentException("No VEvent of the calendar event is targeted by " + patch);
        }
    }

    /**
     * Applies every patch targeting the VEvent, not stopping at the first one modifying it.
     *
     * @return true if the VEvent was modified
     */
    private boolean applyPatches(VEvent vEvent) {
        return patches.stream()
            .filter(patch -> patch.targets(vEvent))
            .map(patch -> patch.apply(vEvent))
            .reduce(false, Boolean::logicalOr);
    }
}
