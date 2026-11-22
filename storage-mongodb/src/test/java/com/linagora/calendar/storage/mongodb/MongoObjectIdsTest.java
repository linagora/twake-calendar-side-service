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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linagora.calendar.storage.OpenPaaSId;

class MongoObjectIdsTest {
    @Test
    void toObjectIdShouldConvertAValidHexadecimalId() {
        ObjectId objectId = new ObjectId();

        assertThat(MongoObjectIds.toObjectId(new OpenPaaSId(objectId.toHexString())))
            .isEqualTo(objectId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "zzzzzzzzzzzzzzzzzzzzzzzz", "0123456789abcdef0123456789"})
    void toObjectIdShouldRejectInvalidIdsWithAMessageNamingTheId(String invalidId) {
        assertThatThrownBy(() -> MongoObjectIds.toObjectId(new OpenPaaSId(invalidId)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Invalid id '" + invalidId + "': expected a 24-character hexadecimal id");
    }
}
