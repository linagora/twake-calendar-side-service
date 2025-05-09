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

public enum UploadableMimeType {
    TEXT_CALENDAR("text/calendar"),
    TEXT_VCARD("text/vcard");

    private final String type;

    UploadableMimeType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public static UploadableMimeType fromType(String type) {
        for (UploadableMimeType uploadableMimeType : UploadableMimeType.values()) {
            if (uploadableMimeType.getType().equalsIgnoreCase(type)) {
                return uploadableMimeType;
            }
        }
        throw new IllegalArgumentException("Unknown MIME type: " + type);
    }
}
