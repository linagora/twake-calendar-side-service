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

package com.linagora.calendar.app;

import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.MailAddress;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.MediaType;

import com.github.fge.lambdas.Throwing;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.amqp.meet.MeetConfiguration;
import com.linagora.calendar.dav.DavModuleTestHelper;

class MeetLinkGenerationTest {
    private static final MailAddress ORGANIZER = Throwing.supplier(() -> new MailAddress("alice@example.com")).get();
    private static final String TOKEN_PATH = "/external-api/v1.0/application/token/";
    private static final String ROOMS_PATH = "/external-api/v1.0/rooms/";
    private static final String ROOM_URL = "https://meet.example.com/vep-txbc-trh";

    private static final ClientAndServer meet = ClientAndServer.startClientAndServer(0);

    @RegisterExtension
    @Order(1)
    private static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY)
            .enableMeet(),
        DavModuleTestHelper.RABBITMQ_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        AppTestHelper.OIDC_BY_PASS_MODULE,
        binder -> binder.bind(MeetConfiguration.class).toInstance(new MeetConfiguration(
            "test-client-id",
            "test-client-secret",
            URI.create("http://127.0.0.1:" + meet.getLocalPort()),
            false,
            Duration.ofSeconds(5),
            Optional.empty())),
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(MeetingConferenceLinkGeneratorProbe.class));

    @BeforeEach
    void setUp() {
        meet.reset();
        meet.when(request().withMethod("POST").withPath(TOKEN_PATH))
            .respond(response().withStatusCode(200).withContentType(MediaType.APPLICATION_JSON)
                .withBody("{\"access_token\":\"jwt\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        meet.when(request().withMethod("POST").withPath(ROOMS_PATH))
            .respond(response().withStatusCode(201).withContentType(MediaType.APPLICATION_JSON)
                .withBody("{\"id\":\"550e8400-e29b-41d4-a716-446655440000\",\"slug\":\"vep-txbc-trh\","
                    + "\"url\":\"" + ROOM_URL + "\"}"));
    }

    @Test
    void shouldCreateAMeetRoomWhenMeetIsConfigured(TwakeCalendarGuiceServer server) {
        String generated = server.getProbe(MeetingConferenceLinkGeneratorProbe.class)
            .generate(ORGANIZER)
            .toString();

        assertThat(generated).isEqualTo(ROOM_URL);
        meet.verify(request().withMethod("POST").withPath(ROOMS_PATH));
    }

    @Test
    void shouldMintTheTokenInTheNameOfTheOrganizer(TwakeCalendarGuiceServer server) {
        server.getProbe(MeetingConferenceLinkGeneratorProbe.class).generate(ORGANIZER);

        assertThat(meet.retrieveRecordedRequests(request().withMethod("POST").withPath(TOKEN_PATH)))
            .hasSize(1)
            .allSatisfy(recorded -> assertThat(recorded.getBodyAsString().replaceAll("\\s", ""))
                .contains("\"scope\":\"" + ORGANIZER.asString() + "\"")
                .contains("\"grant_type\":\"client_credentials\""));
    }
}
