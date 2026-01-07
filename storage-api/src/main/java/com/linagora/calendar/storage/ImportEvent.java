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

import java.net.URI;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.events.Event;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.model.ImportId;

public record ImportEvent(Event.EventId eventId,
                          ImportId importId,
                          URI importURI,
                          String importType,
                          ImportStatus status,
                          Optional<Integer> succeedCount,
                          Optional<Integer> failedCount) implements Event {
    public static final Username USERNAME = Username.of("ImportEvent");

    public static ImportEvent succeeded(ImportId importId,
                                        URI importURI,
                                        String importType,
                                        int succeedCount,
                                        int failedCount) {
        return new ImportEvent(Event.EventId.random(),
            importId,
            importURI,
            importType,
            ImportStatus.SUCCESS,
            Optional.of(succeedCount),
            Optional.of(failedCount));
    }

    public static ImportEvent failed(ImportId importId,
                                     URI importURI,
                                     String importType) {
        return new ImportEvent(Event.EventId.random(),
            importId,
            importURI,
            importType,
            ImportStatus.FAILED,
            Optional.empty(),
            Optional.empty());
    }

    public enum ImportStatus {
        SUCCESS,
        FAILED;

        public static ImportStatus from(String value) {
            Preconditions.checkArgument(StringUtils.isNotEmpty(value), "value must not be null or empty");
            try {
                return ImportStatus.valueOf(value.trim().toUpperCase(Locale.US));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown ImportStatus: " + value, e);
            }
        }
    }

    @Override
    public Username getUsername() {
        return USERNAME;
    }

    @Override
    public boolean isNoop() {
        return false;
    }

    @Override
    public EventId getEventId() {
        return eventId;
    }
}