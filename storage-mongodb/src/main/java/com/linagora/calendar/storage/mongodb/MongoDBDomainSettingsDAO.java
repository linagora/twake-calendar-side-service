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

import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.bson.Document;

import com.linagora.calendar.storage.DefaultCalendarPublicVisibility;
import com.linagora.calendar.storage.DomainSettings;
import com.linagora.calendar.storage.DomainSettingsDAO;
import com.linagora.calendar.storage.DomainSettingsPatch;
import com.linagora.calendar.storage.UserSearchMode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class MongoDBDomainSettingsDAO implements DomainSettingsDAO {

    public static final String COLLECTION = "domain_settings";

    private static final String FIELD_DOMAIN = "domain";
    private static final String FIELD_USER_SEARCH_MODE = "userSearchMode";
    private static final String FIELD_RESOURCE_SEARCH_ENABLED = "resourceSearchEnabled";
    private static final String FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY = "defaultCalendarPublicVisibility";

    private final MongoDatabase database;

    @Inject
    public MongoDBDomainSettingsDAO(MongoDatabase database) {
        this.database = database;
    }

    public static Mono<String> declareIndex(MongoCollection<Document> collection) {
        return Mono.from(collection.createIndex(Indexes.ascending(FIELD_DOMAIN), new IndexOptions().unique(true)));
    }

    @Override
    public Mono<DomainSettings> retrieve(Domain domain) {
        return Mono.from(database.getCollection(COLLECTION)
                .find(Filters.eq(FIELD_DOMAIN, domain.asString()))
                .first())
            .map(this::fromDocument);
    }

    @Override
    public Mono<Void> save(Domain domain, DomainSettings settings) {
        Document setDoc = new Document(FIELD_DOMAIN, domain.asString());
        Document unsetDoc = new Document();

        settings.userSearchMode().ifPresentOrElse(
            mode -> setDoc.append(FIELD_USER_SEARCH_MODE, mode.serialize()),
            () -> unsetDoc.append(FIELD_USER_SEARCH_MODE, ""));
        settings.resourceSearchEnabled().ifPresentOrElse(
            enabled -> setDoc.append(FIELD_RESOURCE_SEARCH_ENABLED, enabled),
            () -> unsetDoc.append(FIELD_RESOURCE_SEARCH_ENABLED, ""));
        settings.defaultCalendarPublicVisibility().ifPresentOrElse(
            visibility -> setDoc.append(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY, visibility.serialize()),
            () -> unsetDoc.append(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY, ""));

        Document update = new Document("$set", setDoc);
        if (!unsetDoc.isEmpty()) {
            update.append("$unset", unsetDoc);
        }

        return Mono.from(database.getCollection(COLLECTION)
                .updateOne(
                    Filters.eq(FIELD_DOMAIN, domain.asString()),
                    update,
                    new UpdateOptions().upsert(true)))
            .then();
    }

    @Override
    public Mono<Void> patch(Domain domain, DomainSettingsPatch patch) {
        Document setDoc = setDoc(patch);
        Document unsetDoc = unsetDoc(patch);

        if (setDoc.isEmpty() && unsetDoc.isEmpty()) {
            return Mono.empty();
        }

        Document update = new Document();
        if (!setDoc.isEmpty()) {
            setDoc.append(FIELD_DOMAIN, domain.asString());
            update.append("$set", setDoc);
        }
        if (!unsetDoc.isEmpty()) {
            update.append("$unset", unsetDoc);
        }

        return Mono.from(database.getCollection(COLLECTION)
                .updateOne(
                    Filters.eq(FIELD_DOMAIN, domain.asString()),
                    update,
                    new UpdateOptions().upsert(true)))
            .then();
    }

    private Document setDoc(DomainSettingsPatch patch) {
        Document setDoc = new Document();
        if (patch.userSearchMode().isModified()) {
            setDoc.append(FIELD_USER_SEARCH_MODE, patch.userSearchMode().get().serialize());
        }
        if (patch.resourceSearchEnabled().isModified()) {
            setDoc.append(FIELD_RESOURCE_SEARCH_ENABLED, patch.resourceSearchEnabled().get());
        }
        if (patch.defaultCalendarPublicVisibility().isModified()) {
            setDoc.append(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY, patch.defaultCalendarPublicVisibility().get().serialize());
        }
        return setDoc;
    }

    private Document unsetDoc(DomainSettingsPatch patch) {
        Document unsetDoc = new Document();
        if (patch.userSearchMode().isRemoved()) {
            unsetDoc.append(FIELD_USER_SEARCH_MODE, "");
        }
        if (patch.resourceSearchEnabled().isRemoved()) {
            unsetDoc.append(FIELD_RESOURCE_SEARCH_ENABLED, "");
        }
        if (patch.defaultCalendarPublicVisibility().isRemoved()) {
            unsetDoc.append(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY, "");
        }
        return unsetDoc;
    }

    private DomainSettings fromDocument(Document doc) {
        DomainSettings.Builder builder = DomainSettings.builder();
        Optional.ofNullable(doc.getString(FIELD_USER_SEARCH_MODE))
            .map(UserSearchMode::deserialize)
            .ifPresent(builder::userSearchMode);
        Optional.ofNullable(doc.getBoolean(FIELD_RESOURCE_SEARCH_ENABLED))
            .ifPresent(builder::resourceSearchEnabled);
        Optional.ofNullable(doc.getString(FIELD_DEFAULT_CALENDAR_PUBLIC_VISIBILITY))
            .map(DefaultCalendarPublicVisibility::deserialize)
            .ifPresent(builder::defaultCalendarPublicVisibility);
        return builder.build();
    }
}
