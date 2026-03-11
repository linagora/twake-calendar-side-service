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

package com.linagora.calendar.smtp.template.content.model;


import java.util.List;
import java.util.Map;

import com.linagora.calendar.storage.event.EventFields.Person;

public record PersonModel(String cn, String email) {

    public Map<String, Object> toPugModel() {
        return Map.of("cn", cn,
            "email", email);
    }

    public static PersonModel from(Person person) {
        return new PersonModel(person.cn(), person.email().asString());
    }

    public static List<PersonModel> fromList(List<Person> people) {
        return people.stream()
            .map(PersonModel::from)
            .toList();
    }
}
