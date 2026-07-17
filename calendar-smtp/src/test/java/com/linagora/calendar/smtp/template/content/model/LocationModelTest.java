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

package com.linagora.calendar.smtp.template.content.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocationModelTest {

    @Test
    void urlEncodedValueShouldEncodeSpacesAsPercent20() {
        assertThat(new LocationModel("37 Rue Pierre Poli, 92130 Issy-les-Moulineaux").toPugModel())
            .containsEntry("urlEncodedValue", "37%20Rue%20Pierre%20Poli%2C%2092130%20Issy-les-Moulineaux");
    }

    @Test
    void urlEncodedValueShouldEncodeNonAsciiCharacters() {
        assertThat(new LocationModel("Café").toPugModel())
            .containsEntry("urlEncodedValue", "Caf%C3%A9");
    }

    @Test
    void urlEncodedValueShouldNotAlterAlreadySafeValue() {
        assertThat(new LocationModel("RoomA").toPugModel())
            .containsEntry("urlEncodedValue", "RoomA");
    }

    @Test
    void toPugModelShouldKeepRawValue() {
        assertThat(new LocationModel("37 Rue Pierre Poli").toPugModel())
            .containsEntry("value", "37 Rue Pierre Poli");
    }
}
