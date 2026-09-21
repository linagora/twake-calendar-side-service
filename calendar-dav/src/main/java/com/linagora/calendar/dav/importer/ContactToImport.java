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

package com.linagora.calendar.dav.importer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.property.Uid;

/**
 * A single contact, as stored on the DAV server. Its UID is rewritten to the resource name it is stored
 * under, so that re-importing an exported address book updates the contacts rather than duplicating them.
 */
public record ContactToImport(String resourceName, VCard vcard, byte[] payload) {

    public static List<ContactToImport> parse(byte[] vcardPayload) {
        return Ezvcard.parse(new String(vcardPayload, StandardCharsets.UTF_8))
            .all()
            .stream()
            .map(ContactToImport::of)
            .toList();
    }

    private static ContactToImport of(VCard vcard) {
        String resourceName = DavResourceName.fromUid(Optional.ofNullable(vcard.getUid())
            .map(Uid::getValue)
            .orElse(null));
        vcard.setUid(new Uid(resourceName));

        return new ContactToImport(resourceName, vcard, Ezvcard.write(vcard)
            .prodId(false)
            .go()
            .getBytes(StandardCharsets.UTF_8));
    }
}
