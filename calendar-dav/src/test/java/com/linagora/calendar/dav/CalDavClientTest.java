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

package com.linagora.calendar.dav;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static com.linagora.calendar.dav.CalDavClient.CalDavExportException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.calendar.storage.MailboxSessionUtil;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;

public class CalDavClientTest {

    @RegisterExtension
    static DockerOpenPaasExtension dockerOpenPaasExtension = new DockerOpenPaasExtension(DockerOpenPaasSetup.SINGLETON);

    private CalDavClient testee;

    @BeforeEach
    void setupEach() throws Exception {
        testee = new CalDavClient(dockerOpenPaasExtension.dockerOpenPaasSetup().davConfiguration());
    }

    private OpenPaaSUser openPaaSUser() {
        return dockerOpenPaasExtension.newTestUser();
    }

    @Test
    void exportShouldSucceed() {
        OpenPaaSUser openPaaSUser = openPaaSUser();
        String exportPayloadAsString = testee.export(CalendarUrlPath.from(openPaaSUser.id()), MailboxSessionUtil.create(openPaaSUser.username()))
            .map(bytes -> StringUtils.trim(new String(bytes, StandardCharsets.UTF_8)))
            .block();

        assertThat(exportPayloadAsString).startsWith("BEGIN:VCALENDAR");
        assertThat(exportPayloadAsString).endsWith("END:VCALENDAR");
    }

    @Test
    void exportShouldThrowWhenInvalidPath() {
        OpenPaaSUser openPaaSUser = openPaaSUser();

        CalendarUrlPath invalidUrlPath = new CalendarUrlPath(URI.create("/invalid/calendars/" + openPaaSUser.id().value() + "/" + openPaaSUser.id().value()));

        assertThatThrownBy(() -> testee.export(invalidUrlPath, MailboxSessionUtil.create(openPaaSUser.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

    @Test
    void exportShouldThrowWhenNotFound() {
        OpenPaaSUser openPaaSUser = openPaaSUser();

        CalendarUrlPath notFoundCalendarUrlPath = CalendarUrlPath.from(new OpenPaaSId(UUID.randomUUID().toString()));

        assertThatThrownBy(() -> testee.export(notFoundCalendarUrlPath, MailboxSessionUtil.create(openPaaSUser.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

    @Test
    void exportShouldThrowWhenPathNotBelongingToUser() {
        OpenPaaSUser openPaaSUser1 = openPaaSUser();
        OpenPaaSUser openPaaSUser2 = openPaaSUser();

        CalendarUrlPath notBelongingCalendarUrlPath = CalendarUrlPath.from(openPaaSUser1.id());

        assertThatThrownBy(() -> testee.export(notBelongingCalendarUrlPath, MailboxSessionUtil.create(openPaaSUser2.username())).block())
            .isInstanceOf(CalDavExportException.class)
            .hasMessageContaining("Failed to export calendar");
    }

}
