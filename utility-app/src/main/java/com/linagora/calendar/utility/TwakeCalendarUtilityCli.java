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

package com.linagora.calendar.utility;

import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.concurrent.Callable;

import org.apache.commons.configuration2.ex.ConfigurationException;

import com.linagora.calendar.utility.cli.PurgeInboxCommand;
import com.linagora.calendar.utility.repository.MongoSchedulingObjectsDAO;
import com.linagora.calendar.utility.service.SchedulingObjectsPurgeService;

import picocli.CommandLine;

@CommandLine.Command(
    name = "tcalendar-cli",
    description = "Twake Calendar Utility CLI",
    mixinStandardHelpOptions = true)
public class TwakeCalendarUtilityCli implements Callable<Integer> {

    public static final int CLI_FINISHED_SUCCESS = 0;

    private final PrintStream out = System.out;

    @Override
    public Integer call() {
        out.println("Twake Calendar Utility CLI - Use --help for available commands.");
        return CLI_FINISHED_SUCCESS;
    }

    public static void main(String[] args) throws ConfigurationException, FileNotFoundException {
        PrintStream out = System.out;
        PrintStream err = System.err;
        int exitCode = execute(out, err, args);
        System.exit(exitCode);
    }

    public static int execute(PrintStream out, PrintStream err, String[] args) throws ConfigurationException, FileNotFoundException {
        MongoBootstrap mongoBootstrap = new MongoBootstrap();
        try {
            MongoSchedulingObjectsDAO schedulingObjectDAO = new MongoSchedulingObjectsDAO(mongoBootstrap.mongoDatabase());
            SchedulingObjectsPurgeService purgeService = new SchedulingObjectsPurgeService(schedulingObjectDAO, out, err);

            return new CommandLine(new TwakeCalendarUtilityCli())
                .addSubcommand(new PurgeInboxCommand(out, err, purgeService))
                .execute(args);
        } finally {
            mongoBootstrap.close();
        }
    }
}