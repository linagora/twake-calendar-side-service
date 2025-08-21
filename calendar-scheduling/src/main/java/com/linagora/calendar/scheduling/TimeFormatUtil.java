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

package com.linagora.calendar.scheduling;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com.ibm.icu.text.MeasureFormat;
import com.ibm.icu.util.Measure;
import com.ibm.icu.util.TimeUnit;

public class TimeFormatUtil {
    public static String formatDuration(Duration durationInput, Locale locale) {
        Duration duration = roundUpToMinutes(durationInput);
        long totalSeconds = duration.getSeconds();

        long days = totalSeconds / (24 * 3600);
        long hours = (totalSeconds % (24 * 3600)) / 3600;
        long minutes = (totalSeconds % 3600) / 60;

        List<Measure> measures = new ArrayList<>();

        if (days >= 1) {
            measures.add(new Measure(days, TimeUnit.DAY));
            if (hours > 0) {
                measures.add(new Measure(hours, TimeUnit.HOUR));
            }
        } else if (hours >= 1) {
            measures.add(new Measure(hours, TimeUnit.HOUR));
            if (minutes > 0) {
                measures.add(new Measure(minutes, TimeUnit.MINUTE));
            }
        } else {
            measures.add(new Measure(minutes, TimeUnit.MINUTE));
        }

        MeasureFormat unitFormatter = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE);

        return measures.stream()
            .map(unitFormatter::format)
            .collect(Collectors.joining(" "));
    }

    private static Duration roundUpToMinutes(Duration duration) {
        long seconds = duration.getSeconds();
        long minutes = (long) Math.ceil(seconds / 60.0);
        return Duration.ofMinutes(minutes);
    }

}
