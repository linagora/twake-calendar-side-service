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

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Indexes.ascending;

import java.util.Date;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.bson.Document;
import org.bson.types.Binary;
import org.bson.types.ObjectId;

import com.linagora.calendar.storage.FileUploadConfiguration;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;
import com.linagora.calendar.storage.model.UploadedMimeType;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBUploadedFileDAO implements UploadedFileDAO {

    public static final String COLLECTION = "twake_calendar_uploaded_files";

    private final MongoCollection<Document> collection;

    @Inject
    public MongoDBUploadedFileDAO(MongoDatabase database, FileUploadConfiguration configuration) {
        this.collection = database.getCollection(COLLECTION);

        IndexOptions ttlIndexOptions = new IndexOptions().expireAfter(configuration.fileExpiration().getSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        Mono.from(collection.createIndex(ascending("created"), ttlIndexOptions)).block();

        Mono.from(collection.createIndex(ascending("username"), new IndexOptions())).block();
    }

    @Override
    public Mono<UploadedFile> getFile(Username username, OpenPaaSId id) {
        return Mono.from(
            collection.find(and(
                eq("_id", new ObjectId(id.value())),
                eq("username", username.asString())
            )).first()
        ).map(this::toUploadedFile);
    }

    @Override
    public Mono<OpenPaaSId> saveFile(Username username, Upload upload) {
        Document doc = new Document()
            .append("username", username.asString())
            .append("fileName", upload.fileName())
            .append("mimeType", upload.uploadedMimeType().getType())
            .append("created", upload.created())
            .append("size", upload.size())
            .append("data", upload.data());

        return Mono.from(collection.insertOne(doc))
            .map(success -> new OpenPaaSId(doc.getObjectId("_id").toHexString()));
    }

    @Override
    public Mono<Void> deleteFile(Username username, OpenPaaSId id) {
        return Mono.from(collection.deleteOne(and(
            eq("_id", new ObjectId(id.value())),
            eq("username", username.asString())
        ))).then();
    }

    @Override
    public Flux<UploadedFile> listFiles(Username username) {
        return Flux.from(
            collection.find(eq("username", username.asString()))
        ).map(this::toUploadedFile);
    }

    private UploadedFile toUploadedFile(Document doc) {
        return new UploadedFile(
            new OpenPaaSId(doc.getObjectId("_id").toHexString()),
            Username.of(doc.getString("username")),
            doc.getString("fileName"),
            UploadedMimeType.fromType(doc.getString("mimeType")),
            doc.get("created", Date.class).toInstant(),
            doc.getLong("size"),
            doc.get("data", Binary.class).getData()
        );
    }
}

