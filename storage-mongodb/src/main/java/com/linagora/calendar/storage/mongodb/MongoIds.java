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

import com.linagora.calendar.storage.OpenPaaSId;

public class MongoIds {
    private MongoIds() {
    }

    // new ObjectId(String) fails with "state should be: hexString has 24 characters", which says nothing of the request.
    public static ObjectId asObjectId(OpenPaaSId id) {
        if (!ObjectId.isValid(id.value())) {
            throw new IllegalArgumentException("Invalid id '" + id.value() + "': expected a 24-character hexadecimal id");
        }
        return new ObjectId(id.value());
    }
}
