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

package com.linagora.calendar.restapi.routes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.restapi.RestApiConfiguration;

public class MeetingConferenceLinkResolverTest {

    private static final URL VISIO_BASE_URL = Throwing.supplier(() -> URI.create("https://meet.linagora.com").toURL()).get();

    @Test
    void resolveShouldAppendGeneratedRoomCodeToVisioBaseUrl() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .visioURL(Optional.of(VISIO_BASE_URL))
            .adminPassword(Optional.of("admin"))
            .build();

        URL resolved = new MeetingConferenceLinkResolver.Visio(configuration, () -> "vep-txbc-trh").resolve();

        assertThat(resolved.toString())
            .isEqualTo("https://meet.linagora.com/vep-txbc-trh");
    }

    @Test
    void resolveShouldGenerateExpectedRoomCodeFormat() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .visioURL(Optional.of(VISIO_BASE_URL))
            .adminPassword(Optional.of("admin"))
            .build();

        URL resolved = new MeetingConferenceLinkResolver.Visio(configuration).resolve();

        assertThat(resolved.toString())
            .matches("https://meet\\.linagora\\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}");
    }
}
