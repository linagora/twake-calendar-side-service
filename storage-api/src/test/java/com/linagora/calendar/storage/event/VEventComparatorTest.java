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

package com.linagora.calendar.storage.event;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.PropertyList;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DtStamp;
import net.fortuna.ical4j.model.property.LastModified;
import net.fortuna.ical4j.model.property.Sequence;
import net.fortuna.ical4j.model.property.Uid;

public class VEventComparatorTest {

    private final VEventComparator comparator = new VEventComparator();


    private VEvent buildEvent(String uid, Integer sequence, Instant lastModified, Instant dtStamp) throws URISyntaxException {
        VEvent event = new VEvent();

        ImmutableList.Builder<Property> propertyBuilder = ImmutableList.<Property>builder()
            .add(new Uid(uid));

        if (sequence != null) {
            propertyBuilder.add(new Sequence(sequence));
        }
        if (lastModified != null) {
            propertyBuilder.add(new LastModified(lastModified));
        }
        if (dtStamp != null) {
            propertyBuilder.add(new DtStamp(dtStamp));
        }
        event.setPropertyList(new PropertyList(propertyBuilder.build()));
        return event;
    }

    @Test
    void shouldSortBySequence() throws Exception {
        VEvent oldEvent = buildEvent("1", 1, null, null);
        VEvent newEvent = buildEvent("1", 2, null, null);

        List<VEvent> events = List.of(oldEvent, newEvent);

        VEvent latest = Collections.max(events, comparator);

        assertThat(latest).isEqualTo(newEvent);
    }


    @Test
    void shouldSortByLastModifiedWhenSequenceEqual() throws Exception {
        Instant lmOld = Instant.parse("2023-01-01T10:00:00Z");
        Instant lmNew = Instant.parse("2023-01-02T10:00:00Z");

        VEvent oldEvent = buildEvent("1", 1, lmOld, null);
        VEvent newEvent = buildEvent("1", 1, lmNew, null);

        List<VEvent> events = List.of(oldEvent, newEvent);

        VEvent latest = Collections.max(events, comparator);

        assertThat(latest).isEqualTo(newEvent);
    }

    @Test
    void shouldSortByDtStampWhenNoLastModified() throws Exception {
        Instant dsOld = Instant.parse("2023-01-01T10:00:00Z");
        Instant dsNew = Instant.parse("2023-01-02T10:00:00Z");

        VEvent oldEvent = buildEvent("1", 1, null, dsOld);
        VEvent newEvent = buildEvent("1", 1, null, dsNew);

        List<VEvent> events = List.of(oldEvent, newEvent);

        VEvent latest = Collections.max(events, comparator);

        assertThat(latest).isEqualTo(newEvent);
    }


    @Test
    void shouldHandleNullProperties() throws Exception {
        VEvent event1 = buildEvent("1", null, null, null);
        VEvent event2 = buildEvent("1", null, null, null);

        List<VEvent> events = new ArrayList<>();
        events.add(event1);
        events.add(event2);

        events.sort(comparator);

        assertThat(events.get(0)).isEqualTo(event1);
        assertThat(events.get(1)).isEqualTo(event2);
    }

    @Test
    void shouldPreferLastModifiedOverDtStamp() throws Exception {
        Instant lmNew = Instant.parse("2023-01-02T10:00:00Z");
        Instant lmOld = Instant.parse("2023-01-01T10:00:00Z");
        Instant dsNew = Instant.parse("2023-01-03T10:00:00Z");

        VEvent eventWithOldLm = buildEvent("1", 1, lmOld, dsNew);
        VEvent eventWithNewLm = buildEvent("1", 1, lmNew, dsNew);

        List<VEvent> events = List.of(eventWithOldLm, eventWithNewLm);

        VEvent latest = Collections.max(events, comparator);

        assertThat(latest).isEqualTo(eventWithNewLm);
    }
}
