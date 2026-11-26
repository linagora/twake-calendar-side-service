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

import java.util.List;

import ezvcard.VCard;
import it.cnr.iit.jscontact.tools.dto.Card;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;

/**
 * jscontact-tools 2.1.0 {@link VCard2JSContact} holds the state of the card being converted (default language of its
 * names, custom time zones that also pile up from one card to the next): an instance can neither be shared between
 * threads nor reused. A new one, with its own configuration, is hence built for every conversion.
 */
final class VCardToJSContact {
    static List<Card> convert(VCard... vCards) throws CardException {
        return VCard2JSContact.builder()
            .config(VCard2JSContactConfig.builder().build())
            .build()
            .convert(vCards);
    }

    private VCardToJSContact() {
    }
}
