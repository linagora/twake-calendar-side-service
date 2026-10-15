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

package com.linagora.calendar.commoncontacts.api;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.linagora.calendar.restapi.routes.PeopleSearchRoute.ResponseDTO;

public enum ContactField {
    EMAIL_ADDRESSES("emailAddresses", dto -> !dto.getEmailAddresses().isEmpty()),
    PHONE_NUMBERS("phoneNumbers", dto -> !dto.getPhoneNumbers().isEmpty()),
    // Matrix ids are not yet part of the contact search results, see ISSUE-1108
    MATRIX_IDS("matrixIds", dto -> false);

    public static ContactField parse(String value) {
        return Stream.of(values())
            .filter(field -> field.value.equals(value))
            .findAny()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported field: '%s'. Supported: %s"
                .formatted(value, supportedValues())));
    }

    private static List<String> supportedValues() {
        return Stream.of(values())
            .map(ContactField::value)
            .toList();
    }

    private final String value;
    private final Predicate<ResponseDTO> presence;

    ContactField(String value, Predicate<ResponseDTO> presence) {
        this.value = value;
        this.presence = presence;
    }

    public String value() {
        return value;
    }

    public boolean isPresentIn(ResponseDTO responseDTO) {
        return presence.test(responseDTO);
    }
}
