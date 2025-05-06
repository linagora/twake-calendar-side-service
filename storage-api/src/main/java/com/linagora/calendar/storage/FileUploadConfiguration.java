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

package com.linagora.calendar.storage;

import java.time.Duration;
import java.util.Optional;

import org.apache.commons.configuration2.Configuration;
import org.apache.james.util.DurationParser;

public record FileUploadConfiguration(Duration fileExpiration) {
    public static final String UPLOADED_FILE_EXPIRATION = "uploadedFile.expiration";
    public static final Duration DEFAULT_EXPIRATION = Duration.ofMinutes(60);

    public static FileUploadConfiguration parse(Configuration configuration) {
        return new FileUploadConfiguration(
            Optional.ofNullable(configuration.getString(UPLOADED_FILE_EXPIRATION))
                .map(DurationParser::parse).orElse(DEFAULT_EXPIRATION));
    }
}