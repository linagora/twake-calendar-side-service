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

package com.linagora.calendar.app.restapi.routes;

import static com.linagora.calendar.app.AppTestHelper.OPENSEARCH_TEST_MODULE;
import static com.linagora.calendar.storage.TestFixture.awaitAtMost;
import static io.restassured.RestAssured.given;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.apache.james.backends.rabbitmq.RabbitMQExtension.IsolationPolicy.WEAK;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import java.util.Set;

import org.apache.http.HttpStatus;
import org.apache.james.backends.opensearch.DockerOpenSearchExtension;
import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.app.AppTestHelper;
import com.linagora.calendar.app.TwakeCalendarConfiguration;
import com.linagora.calendar.app.TwakeCalendarExtension;
import com.linagora.calendar.app.TwakeCalendarGuiceServer;
import com.linagora.calendar.app.modules.CalendarDataProbe;
import com.linagora.calendar.app.modules.MemoryAutoCompleteModule;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiConfiguration;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiConfiguration.Secret;
import com.linagora.calendar.commoncontacts.api.CommonContactsApiServerProbe;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import net.javacrumbs.jsonunit.core.Option;

class CommonContactsOpenSearchPaginationTest {
    private static final String DOMAIN = "open-paas.ltd";
    private static final Username BOB = Username.fromLocalPartWithDomain("bob", DOMAIN);
    private static final String SECRET = "abcdef";

    @RegisterExtension
    @Order(1)
    static RabbitMQExtension rabbitMQExtension = RabbitMQExtension.singletonRabbitMQ()
        .isolationPolicy(WEAK);

    @RegisterExtension
    @Order(2)
    static DockerOpenSearchExtension openSearchExtension = new DockerOpenSearchExtension();

    @RegisterExtension
    @Order(3)
    static TwakeCalendarExtension twakeCalendarExtension = new TwakeCalendarExtension(
        TwakeCalendarConfiguration.builder()
            .configurationFromClasspath()
            .userChoice(TwakeCalendarConfiguration.UserChoice.MEMORY)
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY)
            .autoCompleteChoice(TwakeCalendarConfiguration.AutoCompleteChoice.OPENSEARCH),
        AppTestHelper.BY_PASS_MODULE.apply(rabbitMQExtension),
        OPENSEARCH_TEST_MODULE.apply(openSearchExtension),
        binder -> binder.bind(CommonContactsApiConfiguration.class)
            .toInstance(new CommonContactsApiConfiguration(Optional.of(CommonContactsApiConfiguration.RANDOM_PORT),
                Set.of(new Secret(SECRET)))));

    @Test
    void shouldNotRepeatContactAcrossPagesWhenRelevanceDiffersFromNameOrder(TwakeCalendarGuiceServer server) {
        server.getProbe(CalendarDataProbe.class).addDomain(Domain.of(DOMAIN));
        server.getProbe(CalendarDataProbe.class).addUser(BOB, "secret");
        MemoryAutoCompleteModule.Probe contacts = server.getProbe(MemoryAutoCompleteModule.Probe.class);
        // Zulu ranks higher for "match" in OpenSearch, while Alpha sorts first by display name.
        contacts.add(BOB.asString(), "alpha@domain.tld", "Alpha", "match");
        contacts.add(BOB.asString(), "zulu@domain.tld", "Zulu", "match match match match");

        RequestSpecification specification = new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .setPort(server.getProbe(CommonContactsApiServerProbe.class).getPort().getValue())
            .setBasePath("/api/people/search")
            .addHeader("Authorization", "Bearer " + SECRET)
            .build();

        // Wait for both indexed contacts to become visible before checking pagination.
        awaitAtMost.untilAsserted(() -> assertThatJson(search(specification, 2, 0))
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("""
                [
                  { emailAddresses: [ { value: "alpha@domain.tld" } ] },
                  { emailAddresses: [ { value: "zulu@domain.tld" } ] }
                ]"""));

        String firstPage = search(specification, 1, 0);
        String secondPage = search(specification, 1, 1);

        assertThat(firstPage).isNotEqualTo(secondPage);
        assertThatJson(firstPage)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("[{ emailAddresses: [ { value: 'alpha@domain.tld' } ] }]");
        assertThatJson(secondPage)
            .withOptions(Option.IGNORING_EXTRA_FIELDS)
            .isEqualTo("[{ emailAddresses: [ { value: 'zulu@domain.tld' } ] }]");
    }

    private String search(RequestSpecification specification, int limit, int offset) {
        return given()
            .spec(specification)
            .body("""
                {
                  "user": "bob@open-paas.ltd",
                  "q": "match",
                  "objectTypes": [ "contact" ],
                  "limit": ${limit},
                  "offset": ${offset}
                }"""
                .replace("${limit}", Integer.toString(limit))
                .replace("${offset}", Integer.toString(offset)))
        .when()
            .post()
        .then()
            .statusCode(HttpStatus.SC_OK)
            .extract()
            .body()
            .asString();
    }
}
