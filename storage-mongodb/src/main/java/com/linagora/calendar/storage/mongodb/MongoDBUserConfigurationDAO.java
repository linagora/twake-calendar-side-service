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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.apache.james.mailbox.MailboxSession;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.ConfigurationKey;
import com.linagora.calendar.storage.configuration.ModuleName;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.exception.DomainNotFoundException;
import com.linagora.calendar.storage.exception.UserNotFoundException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class MongoDBUserConfigurationDAO implements UserConfigurationDAO {

    public static class UserConfigurationDeserializeException extends RuntimeException {
        public UserConfigurationDeserializeException(String message, Throwable cause) {
            super(message, cause);
        }

        public UserConfigurationDeserializeException(String message) {
            super(message);
        }
    }

    public static class UserConfigurationSerializeException extends RuntimeException {
        public UserConfigurationSerializeException(String message, Throwable cause) {
            super(message, cause);
        }

        public UserConfigurationSerializeException(String message) {
            super(message);
        }
    }

    private record CombineId(String domainId, String userId) {
        public static CombineId of(OpenPaaSId domainId, OpenPaaSId userId) {
            return new CombineId(domainId.value(), userId.value());
        }
    }

    public static final String FIELD_DOMAIN_ID = "domain_id";
    public static final String FIELD_USER_ID = "user_id";
    public static final String FIELD_MODULES = "modules";
    public static final String PROPERTY_NAME = "name";
    public static final String PROPERTY_CONFIGURATIONS = "configurations";
    public static final String PROPERTY_VALUE = "value";

    public static final String COLLECTION = "configurations";
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDBUserConfigurationDAO.class);

    private final MongoDatabase database;
    private final MongoDBOpenPaaSUserDAO userDAO;
    private final MongoDBOpenPaaSDomainDAO domainDAO;

    @Inject
    @Singleton
    public MongoDBUserConfigurationDAO(MongoDatabase database, MongoDBOpenPaaSUserDAO userDAO, MongoDBOpenPaaSDomainDAO domainDAO) {
        this.database = database;
        this.userDAO = userDAO;
        this.domainDAO = domainDAO;
    }

    @Override
    public Mono<Void> persistConfiguration(Set<ConfigurationEntry> configurationEntries, MailboxSession userSession) {
        return getCombineId(userSession)
            .flatMap(combineId -> upsertUserConfiguration(combineId, convertToDocument(configurationEntries)))
            .doOnError(error -> LOGGER.error("Failed to persist configuration for user: " + userSession.getUser().asString(), error));
    }

    private Mono<Void> upsertUserConfiguration(CombineId combineId, Document modules) {
        Document filter = new Document()
            .append(FIELD_DOMAIN_ID, new ObjectId(combineId.domainId()))
            .append(FIELD_USER_ID, new ObjectId(combineId.userId()));

        Document update = new Document("$set", new Document(FIELD_MODULES, modules.get(FIELD_MODULES)));

        return Mono.from(database.getCollection(COLLECTION)
                .updateOne(filter, update, new UpdateOptions().upsert(true)))
            .then();
    }

    @Override
    public Flux<ConfigurationEntry> retrieveConfiguration(MailboxSession mailboxSession) {
        return getCombineId(mailboxSession)
            .flatMap(this::getModulesByDomainId)
            .map(MongoDBUserConfigurationDAO::toConfigurationEntry)
            .flatMapMany(Flux::fromIterable)
            .doOnError(error -> LOGGER.error("Failed to retrieve configuration for user: " + mailboxSession.getUser().asString(), error));
    }

    private Mono<CombineId> getCombineId(MailboxSession mailboxSession) {
        return Mono.zip(
                domainDAO.retrieve(mailboxSession.getUser().getDomainPart()
                    .orElseThrow(() -> new IllegalArgumentException("Invalid username " + mailboxSession.getUser())))
                    .switchIfEmpty(Mono.error(new DomainNotFoundException(mailboxSession.getUser().getDomainPart().get()))),
                userDAO.retrieve(mailboxSession.getUser())
                    .switchIfEmpty(Mono.error(new UserNotFoundException(mailboxSession.getUser()))))
            .map(tuple -> CombineId.of(tuple.getT1().id(), tuple.getT2().id()));
    }

    private Mono<Document> getModulesByDomainId(CombineId combineId) {
        Bson domainFilter = Filters.eq(FIELD_DOMAIN_ID, new ObjectId(combineId.domainId()));
        Bson userFilter = Filters.eq(FIELD_USER_ID, new ObjectId(combineId.userId()));

        return Mono.from(database.getCollection(COLLECTION)
            .find(Filters.and(domainFilter, userFilter))
            .first());
    }

    public static List<ConfigurationEntry> toConfigurationEntry(Document document) {
        try {
            return document.getList(FIELD_MODULES, Document.class).stream()
                .flatMap(moduleDocument -> {
                    ModuleName moduleName = getModuleName(moduleDocument);
                    return getConfigurationDocuments(moduleDocument)
                        .stream()
                        .map(configDocument ->
                            new ConfigurationEntry(moduleName,
                                getConfigurationKey(configDocument),
                                getConfigurationValue(configDocument)));
                })
                .toList();
        } catch (UserConfigurationDeserializeException e) {
            throw e;
        } catch (RuntimeException runtimeException) {
            throw new UserConfigurationDeserializeException("Failed to deserialize configuration entry: \n" + document.toJson(), runtimeException);
        }
    }

    private static ModuleName getModuleName(Document document) {
        return Optional.ofNullable(document.getString(PROPERTY_NAME))
            .map(ModuleName::new)
            .orElseThrow(() -> new UserConfigurationDeserializeException("Module name is missing. Document: " + document.toJson()));
    }

    private static List<Document> getConfigurationDocuments(Document document) {
        return Optional.ofNullable(document.getList(PROPERTY_CONFIGURATIONS, Document.class))
            .orElseThrow(() -> new UserConfigurationDeserializeException("Configurations component is missing. Document: " + document.toJson()));
    }

    private static ConfigurationKey getConfigurationKey(Document document) {
        return Optional.ofNullable(document.getString(PROPERTY_NAME))
            .map(ConfigurationKey::new)
            .orElseThrow(() -> new UserConfigurationDeserializeException("Configuration key is missing. Document: " + document.toJson()));
    }

    private static JsonNode getConfigurationValue(Document document) {
        if (!document.containsKey(PROPERTY_VALUE)) {
            throw new UserConfigurationDeserializeException("Configuration value is missing. Document: " + document.toJson());
        }
        return Optional.ofNullable(document.get(PROPERTY_VALUE))
            .map(MongoDBUserConfigurationDAO::toJsonNode)
            .orElse(null);
    }

    private static JsonNode toJsonNode(Object value) throws UserConfigurationDeserializeException {
        try {
            if (value instanceof Document document) {
                return MAPPER.readTree(document.toJson());
            }
            return MAPPER.readTree(MAPPER.writeValueAsString(value));
        } catch (JsonProcessingException e) {
            throw new UserConfigurationDeserializeException("Failed to convert value to JsonNode: " + value, e);
        }
    }

    public static Document convertToDocument(Set<ConfigurationEntry> configurationEntries) {
        Map<String, List<Document>> groupedByModule = configurationEntries.stream()
            .collect(Collectors.groupingBy(entry -> entry.moduleName().name(),
                Collectors.mapping(entry ->
                        new Document(PROPERTY_NAME, entry.configurationKey().value())
                            .append(PROPERTY_VALUE, jsonNodeToBsonValue(entry.node())),
                    Collectors.toList())));

        List<Document> modules = groupedByModule.entrySet().stream()
            .map(entry -> new Document(PROPERTY_NAME, entry.getKey())
                .append(PROPERTY_CONFIGURATIONS, entry.getValue()))
            .toList();

        return new Document(FIELD_MODULES, modules);
    }

    private static Object jsonNodeToBsonValue(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            if (node.isValueNode()) {
                return switch (node.getNodeType()) {
                    case STRING -> node.textValue();
                    case BOOLEAN -> node.booleanValue();
                    case NUMBER -> node.numberValue();
                    case NULL -> null;
                    default -> throw new IllegalArgumentException("Unsupported value type: " + node.getNodeType());
                };
            } else if (node.isArray()) {
                return StreamSupport.stream(node.spliterator(), false)
                    .map(MongoDBUserConfigurationDAO::jsonNodeToBsonValue)
                    .toList();
            } else if (node.isObject()) {
                return Document.parse(node.toString());
            } else {
                return Document.parse(MAPPER.writeValueAsString(node));
            }
        } catch (Exception e) {
            throw new UserConfigurationSerializeException("Failed to convert JsonNode to BSON value: " + node, e);
        }
    }

}