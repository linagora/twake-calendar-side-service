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

package com.linagora.calendar.utility.service;

import java.util.Optional;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.property.FormattedName;

/**
 * Sortable full name of a contact, as esn-sabre stores it in the fn_sort field of a card
 * (ESN\CardDAV\Backend\Mongo::getSortableFn): the trimmed FN value, accents and case being ignored by the
 * collation of the index rather than stripped.
 *
 * String::trim strips all the characters up to U+0020 while PHP trim() only strips spaces, tabs, line breaks,
 * NUL and vertical tabs: a full name starting or ending with another control character, such as a form feed,
 * is stored without it here and with it by esn-sabre.
 */
public final class ContactSortName {

    private ContactSortName() {
    }

    /**
     * @return the sortable full name, empty when the vCard has no FN or cannot be parsed
     */
    public static String fromVCard(String cardData) {
        return Optional.ofNullable(cardData)
            .flatMap(ContactSortName::parse)
            .map(VCard::getFormattedName)
            .map(FormattedName::getValue)
            .map(String::trim)
            .orElse("");
    }

    private static Optional<VCard> parse(String cardData) {
        try {
            return Optional.ofNullable(Ezvcard.parse(cardData).first());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
