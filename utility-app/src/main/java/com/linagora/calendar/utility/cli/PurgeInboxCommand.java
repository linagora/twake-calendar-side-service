/************************************************************
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
 ************************************************************/

package com.linagora.calendar.utility.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.Callable;

import org.apache.james.util.DurationParser;

import com.linagora.calendar.utility.service.SchedulingObjectsPurgeService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "purgeInbox", description = "Remove schedulingObjects older than the provided retention period", mixinStandardHelpOptions = true)
public class PurgeInboxCommand implements Callable<Integer> {

    @Option(names = "--retention-period", required = true, description = "Retention period to purge inbox objects (e.g. 1y, 6m, 30d)")
    private String retentionPeriod;

    protected final PrintStream out;
    protected final PrintStream err;
    private final SchedulingObjectsPurgeService purgeService;

    public PurgeInboxCommand(PrintStream out, PrintStream err, SchedulingObjectsPurgeService purgeService) {
        this.out = out;
        this.err = err;
        this.purgeService = purgeService;
    }

    @Override
    public Integer call() {
        try {
            Duration parsedRetention = DurationParser.parse(retentionPeriod, ChronoUnit.DAYS);
            out.printf("Starting purgeInbox task with retention period: %d days (%s)%n", parsedRetention.toDays(), parsedRetention);
            purgeService.purge(parsedRetention)
                .block();
            out.println("PurgeInbox task completed.");
            return 0;
        } catch (Exception e) {
            err.printf("Error while executing purgeInbox: %s%n", e);
            return 1;
        }
    }
}
