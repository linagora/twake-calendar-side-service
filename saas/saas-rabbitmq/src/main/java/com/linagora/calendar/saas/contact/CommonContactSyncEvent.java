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

package com.linagora.calendar.saas.contact;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommonContactSyncEvent(Action action, Audience audience, URI path, String syncId, Boolean complete, Long count)
    implements CommonContactMessage {

    public enum Action {
        SYNC_START, SYNC_END
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public CommonContactSyncEvent {
        Preconditions.checkNotNull(action, "action must not be null");
        Preconditions.checkNotNull(audience, "audience must not be null");
        Preconditions.checkNotNull(path, "path must not be null");
        Preconditions.checkArgument(syncId != null && !syncId.isBlank(), "syncId must not be blank");
        Preconditions.checkArgument(action == Action.SYNC_START ? complete == null && count == null
            : complete != null && count != null && count >= 0, "Only SYNC_END carries complete and a non-negative count");
    }

    public static CommonContactSyncEvent start(Audience audience, URI path, String syncId) {
        return new CommonContactSyncEvent(Action.SYNC_START, audience, path, syncId, null, null);
    }

    public static CommonContactSyncEvent end(Audience audience, URI path, String syncId, boolean complete, long count) {
        return new CommonContactSyncEvent(Action.SYNC_END, audience, path, syncId, complete, count);
    }

    @Override
    public byte[] serialize() throws JsonProcessingException {
        return OBJECT_MAPPER.writeValueAsBytes(this);
    }
}
