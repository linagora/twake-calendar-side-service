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

package com.linagora.calendar.storage;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.user.api.DeleteUserDataTaskStep;

import reactor.core.publisher.Mono;

public class OpenPaaSUserDeletionTaskStep implements DeleteUserDataTaskStep {

    private final OpenPaaSUserDAO userDAO;

    @Inject
    public OpenPaaSUserDeletionTaskStep(OpenPaaSUserDAO userDAO) {
        this.userDAO = userDAO;
    }

    @Override
    public StepName name() {
        return new StepName("OpenPaaSUserDeletionTaskStep");
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public Mono<Void> deleteUserData(Username username) {
        return userDAO.delete(username);
    }
}
