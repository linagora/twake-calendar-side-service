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

import org.apache.james.backends.rabbitmq.RabbitMQExtension;
import org.apache.james.core.MailAddress;
import org.apache.james.utils.GuiceProbe;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.github.fge.lambdas.Throwing;
import com.google.inject.multibindings.Multibinder;
import com.linagora.calendar.dav.DavModuleTestHelper;

class VisioLinkGenerationTest {
    private static final MailAddress ORGANIZER = Throwing.supplier(() -> new MailAddress("alice@example.com")).get();

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
            .dbChoice(TwakeCalendarConfiguration.DbChoice.MEMORY),
        DavModuleTestHelper.RABBITMQ_MODULE.apply(rabbitMQExtension),
        DavModuleTestHelper.BY_PASS_MODULE,
        AppTestHelper.OIDC_BY_PASS_MODULE,
        binder -> Multibinder.newSetBinder(binder, GuiceProbe.class)
            .addBinding().to(MeetingConferenceLinkGeneratorProbe.class));

    @Test
    void shouldGenerateARoomCodeWhenMeetIsNotConfigured(TwakeCalendarGuiceServer server) {
        String generated = server.getProbe(MeetingConferenceLinkGeneratorProbe.class)
            .generate(ORGANIZER)
            .toString();

        assertThat(generated).matches("^https?://.+/[a-z]{3}-[a-z]{4}-[a-z]{3}$");
    }
}
