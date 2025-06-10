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

package com.linagora.calendar.storage.eventsearch;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.DeleteUserDataTaskStep;
import org.apache.james.vacation.api.AccountId;

import reactor.core.publisher.Mono;

public class CalendarSearchDeletionTaskStep implements DeleteUserDataTaskStep {

    private final CalendarSearchService calendarSearchService;

    @Inject
    public CalendarSearchDeletionTaskStep(CalendarSearchService calendarSearchService) {
        this.calendarSearchService = calendarSearchService;
    }

    @Override
    public StepName name() {
        return new StepName("CalendarSearchUserDeletionTaskStep");
    }

    @Override
    public int priority() {
        return 1;
    }

    @Override
    public Mono<Void> deleteUserData(Username username) {
        return calendarSearchService.deleteAll(AccountId.fromUsername(username));
    }
}
