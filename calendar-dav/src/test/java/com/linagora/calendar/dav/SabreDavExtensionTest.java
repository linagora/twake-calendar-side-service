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
 *                                                                  *
 *  This file was taken and adapted from the Apache James project.  *
 *                                                                  *
 *  https://james.apache.org                                        *
 *                                                                  *
 *  It was originally licensed under the Apache V2 license.         *
 *                                                                  *
 *  http://www.apache.org/licenses/LICENSE-2.0                      *
 ********************************************************************/

package com.linagora.calendar.dav;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ContainerState;

import com.linagora.calendar.storage.OpenPaaSUser;

public class SabreDavExtensionTest {

    @RegisterExtension
    static SabreDavExtension sabreDavExtension = new SabreDavExtension(DockerSabreDavSetup.SINGLETON);

    @Test
    void allServersShouldStartSuccessfully() {
       assertTrue(sabreDavExtension.dockerSabreDavSetup().getAllContainers()
           .stream().allMatch(ContainerState::isRunning));
    }

    @Test
    void newTestUserShouldSucceed() {
        OpenPaaSUser user = sabreDavExtension.newTestUser();
        assertThat(user.username()).isNotNull();
        assertThat(user.id()).isNotNull();
    }
}
