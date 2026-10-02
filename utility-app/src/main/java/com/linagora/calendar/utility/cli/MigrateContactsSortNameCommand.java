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

package com.linagora.calendar.utility.cli;

import java.io.PrintStream;
import java.util.concurrent.Callable;

import com.linagora.calendar.utility.service.ContactsSortNameMigrationService;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "migrateContactsSortName",
    description = "Store the sortable full name (fn_sort) of the contacts written before esn-sabre stored it. "
        + "Run against the esn-sabre database.",
    mixinStandardHelpOptions = true)
public class MigrateContactsSortNameCommand implements Callable<Integer> {

    @Option(names = "--batch-size", description = "Number of contacts updated per batch (default: ${DEFAULT-VALUE})")
    private int batchSize = ContactsSortNameMigrationService.DEFAULT_BATCH_SIZE;

    protected final PrintStream out;
    protected final PrintStream err;
    private final ContactsSortNameMigrationService migrationService;

    public MigrateContactsSortNameCommand(PrintStream out, PrintStream err, ContactsSortNameMigrationService migrationService) {
        this.out = out;
        this.err = err;
        this.migrationService = migrationService;
    }

    @Override
    public Integer call() {
        try {
            out.printf("Starting migrateContactsSortName task with batch size %d%n", batchSize);
            migrationService.migrate(batchSize)
                .block();
            out.println("MigrateContactsSortName task completed.");
            return 0;
        } catch (Exception e) {
            err.printf("Error while executing migrateContactsSortName: %s%n", e);
            return 1;
        }
    }
}
