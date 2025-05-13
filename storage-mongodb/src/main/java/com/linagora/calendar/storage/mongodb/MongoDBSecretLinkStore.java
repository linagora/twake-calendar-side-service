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

import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxSession;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.google.common.base.Preconditions;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.linagora.calendar.storage.secretlink.SecretLinkPermissionChecker;
import com.linagora.calendar.storage.secretlink.SecretLinkStore;
import com.linagora.calendar.storage.secretlink.SecretLinkToken;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class MongoDBSecretLinkStore implements SecretLinkStore {
    public static final String COLLECTION = "secretlinks";

    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_CALENDAR_HOME_ID = "calendarHomeId";
    public static final String FIELD_CALENDAR_ID = "calendarId";
    public static final String FIELD_TOKEN = "token";


    private final MongoDatabase database;
    private final SecretLinkPermissionChecker permissionChecker;
    private final MongoDBOpenPaaSUserDAO userDAO;

    @Inject
    public MongoDBSecretLinkStore(MongoDatabase database,
                                  SecretLinkPermissionChecker permissionChecker,
                                  MongoDBOpenPaaSUserDAO userDAO) {
        this.database = database;
        this.permissionChecker = permissionChecker;
        this.userDAO = userDAO;
    }

    @Override
    public Mono<SecretLinkToken> generateSecretLink(CalendarURL url, MailboxSession session) {
        return permissionChecker.assertPermissions(url, session)
            .then(getUserOpenPaaSId(session))
            .flatMap(userId -> upsertSecretLink(userId, url));
    }

    private Mono<SecretLinkToken> upsertSecretLink(OpenPaaSId userId, CalendarURL url) {
        Function<SecretLinkToken, Document> documentFunction = token -> new Document()
            .append(FIELD_USER_ID, new ObjectId(userId.value()))
            .append(FIELD_CALENDAR_HOME_ID, url.base().value())
            .append(FIELD_CALENDAR_ID, url.calendarId().value())
            .append(FIELD_TOKEN, token.value());

        Bson filter = Filters.and(
            Filters.eq(FIELD_USER_ID, new ObjectId(userId.value())),
            Filters.eq(FIELD_CALENDAR_HOME_ID, url.base().value()),
            Filters.eq(FIELD_CALENDAR_ID, url.calendarId().value()));

        return Mono.fromCallable(SecretLinkToken::generate)
            .flatMap(token -> Mono.from(database.getCollection(COLLECTION)
                    .replaceOne(filter, documentFunction.apply(token), new ReplaceOptions().upsert(true)))
                .thenReturn(token));
    }

    @Override
    public Mono<SecretLinkToken> getSecretLink(CalendarURL url, MailboxSession session) {
        return permissionChecker.assertPermissions(url, session)
            .then(getUserOpenPaaSId(session))
            .flatMap(userId -> getSecretLinkDocument(url, userId))
            .map(document -> new SecretLinkToken(document.getString(FIELD_TOKEN)))
            .switchIfEmpty(generateSecretLink(url, session));
    }

    @Override
    public Mono<Username> checkSecretLink(CalendarURL url, SecretLinkToken token) {
        Preconditions.checkArgument(token != null, "token must not be null");
        Preconditions.checkArgument(url != null, "url must not be null");
        return Mono.from(database.getCollection(COLLECTION)
            .find(Filters.and(
                Filters.eq(FIELD_TOKEN, token.value()),
                Filters.eq(FIELD_CALENDAR_HOME_ID, url.base().value()),
                Filters.eq(FIELD_CALENDAR_ID, url.calendarId().value())))
            .first())
            .map(document -> new OpenPaaSId(document.getObjectId(FIELD_USER_ID).toHexString()))
            .flatMap(userDAO::retrieve)
            .map(OpenPaaSUser::username);
    }

    private Mono<Document> getSecretLinkDocument(CalendarURL url, OpenPaaSId userId) {
        return Mono.from(database.getCollection(COLLECTION)
            .find(Filters.and(
                Filters.eq(FIELD_USER_ID, new ObjectId(userId.value())),
                Filters.eq(FIELD_CALENDAR_HOME_ID, url.base().value()),
                Filters.eq(FIELD_CALENDAR_ID, url.calendarId().value())))
            .first());
    }

    private Mono<OpenPaaSId> getUserOpenPaaSId(MailboxSession session) {
        return userDAO.retrieve(session.getUser())
            .map(OpenPaaSUser::id)
            .switchIfEmpty(Mono.error(new UserNotFoundException(session.getUser())));
    }
}
