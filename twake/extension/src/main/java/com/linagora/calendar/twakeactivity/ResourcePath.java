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

package com.linagora.calendar.twakeactivity;

import java.util.List;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;

// The DAV path of a created resource: calendars/{home}/{calendar}/{event}.ics or
// addressbooks/{home}/{addressBook}/{contact}.vcf, without the leading slash sabre sometimes writes.
public record ResourcePath(Collection collection, List<String> segments) {
    public enum Collection {
        CALENDARS("calendars", "calendar"),
        ADDRESS_BOOKS("addressbooks", "addressbook");

        private final String root;
        private final String containerField;

        Collection(String root, String containerField) {
            this.root = root;
            this.containerField = containerField;
        }

        // The data field naming the calendar or the address book that holds the resource.
        public String containerField() {
            return containerField;
        }
    }

    private static final int SEGMENTS = 4;

    public static ResourcePath parse(String path, Collection collection) {
        List<String> segments = Splitter.on('/').omitEmptyStrings().splitToList(path);
        if (segments.size() != SEGMENTS || !segments.getFirst().equals(collection.root)) {
            throw new IllegalArgumentException("Unexpected " + collection.root + " resource path '" + StringUtils.left(path, 255) + "'");
        }
        return new ResourcePath(collection, segments);
    }

    public ResourcePath {
        Preconditions.checkArgument(segments.size() == SEGMENTS, "A resource path has %s segments", SEGMENTS);
        segments = List.copyOf(segments);
    }

    public String path() {
        return String.join("/", segments);
    }

    public String container() {
        return String.join("/", segments.subList(0, SEGMENTS - 1));
    }

    // The calendar home or address book home id.
    public String homeId() {
        return segments.get(1);
    }
}
