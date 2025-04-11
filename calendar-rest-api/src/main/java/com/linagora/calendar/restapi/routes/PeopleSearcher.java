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

package com.linagora.calendar.restapi.routes;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.mail.internet.AddressException;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Username;
import org.apache.james.jmap.api.model.AccountId;
import org.reactivestreams.Publisher;

import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.tmail.james.jmap.contact.ContactFields;
import com.linagora.tmail.james.jmap.contact.EmailAddressContact;
import com.linagora.tmail.james.jmap.contact.EmailAddressContactSearchEngine;

public interface PeopleSearcher {

    String objectType();

    Publisher<EmailAddressContact> search(Username username, String query, int limit);

    default Integer priority() {
        return 0;
    }

    class PeopleContactSearcher implements PeopleSearcher {

        public static final String OBJECT_TYPE = "contact";

        private final EmailAddressContactSearchEngine contactSearchEngine;

        @Inject
        @Singleton
        public PeopleContactSearcher(EmailAddressContactSearchEngine contactSearchEngine) {
            this.contactSearchEngine = contactSearchEngine;
        }

        @Override
        public String objectType() {
            return OBJECT_TYPE;
        }

        @Override
        public Publisher<EmailAddressContact> search(Username username, String query, int limit) {
            return contactSearchEngine.autoComplete(AccountId.fromString(username.asString()), query, limit);
        }

        @Override
        public Integer priority() {
            return 1;
        }
    }

    class PeopleUserSearcher implements PeopleSearcher {

        public static final String OBJECT_TYPE = "user";

        private final OpenPaaSUserDAO userDAO;

        @Inject
        @Singleton
        public PeopleUserSearcher(OpenPaaSUserDAO userDAO) {
            this.userDAO = userDAO;
        }

        @Override
        public String objectType() {
            return OBJECT_TYPE;
        }

        @Override
        public Publisher<EmailAddressContact> search(Username username, String query, int limit) {
            return userDAO.list()
                .filter(matchesQuery(query))
                .map(toContact());
        }

        private Predicate<OpenPaaSUser> matchesQuery(String query) {
            return user -> StringUtils.containsIgnoreCase(user.username().asString(), query)
                || StringUtils.containsIgnoreCase(user.firstname(), query)
                || StringUtils.containsIgnoreCase(user.lastname(), query);
        }

        private Function<OpenPaaSUser, EmailAddressContact> toContact() {
            return user -> {
                try {
                    ContactFields contactFields = new ContactFields(user.username().asMailAddress(), user.firstname(), user.lastname());
                    return new EmailAddressContact(UUID.nameUUIDFromBytes(user.id().value().getBytes()), contactFields);
                } catch (AddressException e) {
                    throw new IllegalArgumentException("Can not parse mailAddress for user: " + user.username().asString(), e);
                }
            };
        }
    }

}
