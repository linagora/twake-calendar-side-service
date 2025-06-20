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

package com.linagora.calendar.smtp.template;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.james.filesystem.api.FileSystem;
import org.apache.james.server.core.filesystem.FileSystemImpl;
import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableMap;

public class HtmlBodyRendererTest {
    static FileSystem fileSystem = FileSystemImpl.forTesting();

    @Test
    void renderShouldSucceed() throws Exception {
        File templateDirectory = fileSystem.getFile("classpath://templates");
        HtmlBodyRenderer htmlBodyRenderer = HtmlBodyRenderer.forPath(templateDirectory.getAbsolutePath());

        Map<String, Object> model = ImmutableMap.of(
            "content", Map.of(
                "baseUrl", "http://localhost:8080",
                "jobFailedList", List.of(ImmutableMap.of("email", "email1@domain.tld"),
                    ImmutableMap.of("email", "email2@domain.tld")),
                "jobSucceedCount", 9,
                "jobFailedCount", 2));

        String result = htmlBodyRenderer.render(model);

        assertThat(result.trim())
            .isEqualTo("""
                <!DOCTYPE html><html class="mail"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"></head><body><div class="wrapper mail-content"><div class="grid-container"><div class="header"><div class="logo"><a href="http://localhost:8080"><img src="cid:logo" alt="OpenPaas Logo"></a></div><div class="subject"><div class="title">Import Report</div></div></div><div class="import"><span>Your Import is done see below the report :<ul><li> Contact(s) imported successfully</li><li> Contact(s) not imported</li></ul></span></div></div></div></body></html>""".trim());
    }

}
