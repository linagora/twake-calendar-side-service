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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.apache.james.core.MailAddress;
import org.junit.jupiter.api.Test;

import com.github.fge.lambdas.Throwing;
import com.linagora.calendar.amqp.meet.MeetApplicationClient;
import com.linagora.calendar.amqp.meet.MeetConfiguration;
import com.linagora.calendar.restapi.RestApiConfiguration;

import reactor.core.publisher.Mono;

public class MeetingConferenceLinkGeneratorTest {

    private static final MailAddress ORGANIZER = Throwing.supplier(() -> new MailAddress("alice@example.com")).get();
    private static final URL VISIO_BASE_URL = Throwing.supplier(() -> URI.create("https://meet.linagora.com").toURL()).get();

    @Test
    void resolveShouldAppendGeneratedRoomCodeToVisioBaseUrl() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .visioURL(Optional.of(VISIO_BASE_URL))
            .adminPassword(Optional.of("admin"))
            .build();

        URL resolved = new MeetingConferenceLinkGenerator.Visio(configuration, () -> "vep-txbc-trh").generate(ORGANIZER).block();

        assertThat(resolved.toString())
            .isEqualTo("https://meet.linagora.com/vep-txbc-trh");
    }

    @Test
    void resolveShouldGenerateExpectedRoomCodeFormat() {
        RestApiConfiguration configuration = RestApiConfiguration.builder()
            .visioURL(Optional.of(VISIO_BASE_URL))
            .adminPassword(Optional.of("admin"))
            .build();

        URL resolved = new MeetingConferenceLinkGenerator.Visio(configuration).generate(ORGANIZER).block();

        assertThat(resolved.toString())
            .matches("https://meet\\.linagora\\.com/[a-z]{3}-[a-z]{4}-[a-z]{3}");
    }

    @Test
    void meetGeneratorShouldReturnTheUrlOfTheRoomCreatedForTheOrganizer() {
        List<MailAddress> scopedTo = new ArrayList<>();
        MeetConfiguration configuration = new MeetConfiguration("client", "secret",
            URI.create("http://meet.invalid"), false, Duration.ofSeconds(5), Optional.empty());
        MeetApplicationClient client = new MeetApplicationClient(configuration) {
            @Override
            public Mono<MeetApplicationClient.MeetToken> fetchToken(MailAddress user) {
                scopedTo.add(user);
                return Mono.just(new MeetApplicationClient.MeetToken("jwt"));
            }

            @Override
            public Mono<MeetApplicationClient.Room> createRoom(MeetApplicationClient.MeetToken token) {
                return Mono.just(new MeetApplicationClient.Room(
                    new MeetApplicationClient.RoomId(UUID.randomUUID()),
                    new MeetApplicationClient.RoomSlug("vep-txbc-trh"),
                    Throwing.supplier(() -> URI.create("https://meet.example.com/vep-txbc-trh").toURL()).get()));
            }
        };

        URL generated = new MeetingConferenceLinkGenerator.Meet(client).generate(ORGANIZER).block();

        assertThat(generated.toString()).isEqualTo("https://meet.example.com/vep-txbc-trh");
        assertThat(scopedTo).containsExactly(ORGANIZER);
    }
}
