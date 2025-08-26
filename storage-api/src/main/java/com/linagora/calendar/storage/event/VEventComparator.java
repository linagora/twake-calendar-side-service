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

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

import com.google.common.collect.ComparisonChain;

import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.component.VEvent;

/**
 * Comparator for sorting {@link VEvent} instances by their "version".
 *
 * <p>The comparator implements the following priority rules (ascending order):
 * <ol>
 *   <li>First by {@code SEQUENCE} (higher sequence means newer version).</li>
 *   <li>If {@code SEQUENCE} is equal or missing, then by {@code LAST-MODIFIED} (later is newer).</li>
 *   <li>If {@code LAST-MODIFIED} is also missing or equal, then by {@code DTSTAMP} (later is newer).</li>
 * </ol>
 *
 * <p>⚠️ Note:
 * <ul>
 *   <li>This comparator sorts in <b>ascending order</b>. That means:
 *       <ul>
 *         <li>The <b>first element</b> (index 0) after sorting is the oldest version.</li>
 *         <li>The <b>last element</b> (index size-1) after sorting is the newest version.</li>
 *       </ul>
 *   </li>
 *   <li>To directly get the newest event with {@code Collections.min/max}, use
 *       {@code Collections.max(events, new VEventComparator())}.</li>
 * </ul>
 *
 */
public class VEventComparator implements Comparator<VEvent> {

    @Override
    public int compare(VEvent e1, VEvent e2) {
        Integer seq1 = parseIntProperty(e1, Property.SEQUENCE).orElse(0);
        Integer seq2 = parseIntProperty(e2, Property.SEQUENCE).orElse(0);

        Instant lastModified1 = parseDateTimeProperty(e1, Property.LAST_MODIFIED).orElse(null);
        Instant lastModified2 = parseDateTimeProperty(e2, Property.LAST_MODIFIED).orElse(null);

        Instant dtStamp1 = parseDateTimeProperty(e1, Property.DTSTAMP).orElse(null);
        Instant dtStamp2 = parseDateTimeProperty(e2, Property.DTSTAMP).orElse(null);

        return ComparisonChain.start()
            .compare(seq1, seq2)
            .compare(lastModified1, lastModified2, Comparator.nullsFirst(Comparator.naturalOrder()))
            .compare(dtStamp1, dtStamp2, Comparator.nullsFirst(Comparator.naturalOrder()))
            .result();
    }

    private Optional<Integer> parseIntProperty(VEvent event, String propertyName) {
        return event.getProperty(propertyName)
            .map(p -> {
                try {
                    return Integer.parseInt(p.getValue());
                } catch (NumberFormatException e) {
                    return 0;
                }
            });
    }

    private Optional<Instant> parseDateTimeProperty(VEvent event, String propertyName) {
        return event.getProperty(propertyName)
            .flatMap(EventParseUtils::parseTimeAsInstant);
    }
}
