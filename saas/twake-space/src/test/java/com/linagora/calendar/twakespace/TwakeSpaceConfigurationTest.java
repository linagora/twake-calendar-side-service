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

package com.linagora.calendar.twakespace;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.commons.configuration2.PropertiesConfiguration;
import org.junit.jupiter.api.Test;

class TwakeSpaceConfigurationTest {
    @Test
    void fromShouldDefaultToTheTwakeSpaceExchanges() {
        assertThat(TwakeSpaceConfiguration.from(new PropertiesConfiguration()))
            .isEqualTo(new TwakeSpaceConfiguration("space", "activity", "twake-space"));
    }

    @Test
    void fromShouldReadTheConfiguredExchanges() {
        PropertiesConfiguration configuration = new PropertiesConfiguration();
        configuration.addProperty("twakespace.exchange", "custom-space");
        configuration.addProperty("twakespace.activity.exchange", "custom-activity");
        configuration.addProperty("twakespace.command.exchange", "custom-command");

        assertThat(TwakeSpaceConfiguration.from(configuration))
            .isEqualTo(new TwakeSpaceConfiguration("custom-space", "custom-activity", "custom-command"));
    }
}
