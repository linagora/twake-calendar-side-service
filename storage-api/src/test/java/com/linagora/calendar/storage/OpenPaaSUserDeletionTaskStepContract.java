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

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.james.core.Username;
import org.junit.jupiter.api.Test;

public interface OpenPaaSUserDeletionTaskStepContract {

    Username USERNAME = Username.of("user@domain.tld");

    OpenPaaSUserDAO userDAO();

    OpenPaaSUserDeletionTaskStep testee();

    @Test
    default void deleteUserDataShouldSucceed() {
        userDAO().add(USERNAME).block();
        testee().deleteUserData(USERNAME).block();

        assertThat(userDAO().retrieve(USERNAME).blockOptional()).isEmpty();
    }

    @Test
    default void deleteUserDataShouldNotAffectOtherUsers() {
        userDAO().add(USERNAME).block();
        Username other = Username.of("other@domain.tld");
        userDAO().add(other).block();
        testee().deleteUserData(USERNAME).block();

        assertThat(userDAO().retrieve(other).blockOptional()).isPresent();
    }
}
