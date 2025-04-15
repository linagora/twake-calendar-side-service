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

import org.junit.jupiter.api.BeforeEach;

import com.linagora.calendar.storage.configuration.UserConfigurationDAO;

public class MemoryUserConfigurationDAOTest implements UserConfigurationDAOContract {

    private MemoryUserConfigurationDAO testee;

    private MemoryOpenPaaSUserDAO memoryUserDAO;

    @BeforeEach
    void setup() {
        memoryUserDAO = new MemoryOpenPaaSUserDAO();
        testee = new MemoryUserConfigurationDAO(memoryUserDAO);

        memoryUserDAO.add(USERNAME).block();
        memoryUserDAO.add(USERNAME_2).block();
    }

    @Override
    public UserConfigurationDAO testee() {
        return testee;
    }
}
