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

package com.linagora.calendar.storage.model;

import java.time.Instant;
import java.util.List;

import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ResourceInsertRequest;
import com.linagora.calendar.storage.ResourceUpdateRequest;

public record Resource(ResourceId id,
                       List<ResourceAdministrator> administrators,
                       OpenPaaSId creator,
                       boolean deleted,
                       String description,
                       OpenPaaSId domain,
                       String icon,
                       String name,
                       Instant creation,
                       Instant updated,
                       String type) {

    public static final boolean DELETED = true;

    public static Resource from(ResourceId id, ResourceInsertRequest req) {
        return new Resource(
            id,
            req.administrators(),
            req.creator(),
            req.deleted(),
            req.description(),
            req.domain(),
            req.icon(),
            req.name(),
            req.creation(),
            req.updated(),
            req.type()
        );
    }

    public Resource update(ResourceUpdateRequest req, Instant updated) {
        return new Resource(
            this.id,
            req.administrators().orElse(this.administrators),
            this.creator,
            this.deleted,
            req.description().orElse(this.description),
            this.domain,
            req.icon().orElse(this.icon),
            req.name().orElse(this.name),
            this.creation,
            updated,
            this.type
        );
    }

    public Resource markAsDeleted(Instant updated) {
        return new Resource(
            this.id,
            this.administrators,
            this.creator,
            DELETED,
            this.description,
            this.domain,
            this.icon,
            this.name,
            this.creation,
            updated,
            this.type
        );
    }
}
