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

package com.linagora.calendar.storage.mongodb;

import org.bson.types.ObjectId;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.OpenPaaSId;

public class MongoObjectIds {
    private MongoObjectIds() {
    }

    public static ObjectId toObjectId(OpenPaaSId id) {
        Preconditions.checkArgument(ObjectId.isValid(id.value()),
            "Invalid id '%s': expected a 24-character hexadecimal id", id.value());
        return new ObjectId(id.value());
    }
}
