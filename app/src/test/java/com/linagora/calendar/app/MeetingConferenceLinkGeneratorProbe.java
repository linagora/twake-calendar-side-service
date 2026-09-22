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

import java.net.URL;

import jakarta.inject.Inject;

import org.apache.james.core.MailAddress;
import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.restapi.routes.MeetingConferenceLinkGenerator;

public class MeetingConferenceLinkGeneratorProbe implements GuiceProbe {
    private final MeetingConferenceLinkGenerator generator;

    @Inject
    public MeetingConferenceLinkGeneratorProbe(MeetingConferenceLinkGenerator generator) {
        this.generator = generator;
    }

    public URL generate(MailAddress organizer) {
        return generator.generate(organizer).block();
    }
}
