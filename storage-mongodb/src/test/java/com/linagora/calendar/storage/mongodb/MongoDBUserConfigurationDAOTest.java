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

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static net.javacrumbs.jsonunit.core.Option.IGNORING_ARRAY_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.UserConfigurationDAOContract;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.mongodb.MongoDBUserConfigurationDAO.UserConfigurationDeserializeException;
import com.mongodb.reactivestreams.client.MongoDatabase;

import reactor.core.publisher.Mono;

public class MongoDBUserConfigurationDAOTest implements UserConfigurationDAOContract {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of("domains", "users", "configurations"));

    private MongoDBOpenPaaSUserDAO userDAO;
    private MongoDBOpenPaaSDomainDAO domainDAO;
    private MongoDBUserConfigurationDAO userConfigurationDAO;
    private OpenPaaSDomain openPaaSDomain;
    private OpenPaaSUser openPaaSUser;

    @BeforeEach
    void setUp() {
        domainDAO = new MongoDBOpenPaaSDomainDAO(mongo.getDb());
        userDAO = new MongoDBOpenPaaSUserDAO(mongo.getDb(), domainDAO);

        userConfigurationDAO = new MongoDBUserConfigurationDAO(mongo.getDb(), userDAO, domainDAO);
        openPaaSDomain = domainDAO.add(DOMAIN).block();
        openPaaSUser = userDAO.add(USERNAME).block();
        userDAO.add(USERNAME_2).block();
    }

    @AfterEach
    void tearDown() {
        resetCollection();
    }

    @Override
    public UserConfigurationDAO testee() {
        return userConfigurationDAO;
    }

    private void resetCollection() {
        Mono.from(mongo.getDb().getCollection("configurations").deleteMany(new Document())).block();
    }

    @Test
    void retrieveShouldReturnCorrectExistConfiguration() {
        String configurationValue = """
            [
                {
                    "name": "linagora.esn.unifiedinbox",
                    "configurations": [
                        { "name": "useEmailLinks", "value": true }
                    ]
                },
                {
                    "name": "core",
                    "configurations": [
                        { "name": "language", "value": "vi" },
                        { "name": "datetime", "value": { "timeZone": "America/Adak", "use24hourFormat": true } },
                        { "name": "homePage", "value": "contact" },
                        { "name": "businessHours", "value": [ { "daysOfWeek": [1,2,3,4,5,6], "start": "9:0", "end": "17:0" } ] }
                    ]
                }
            ]""";

        insertUserConfiguration(mongo.getDb(), openPaaSDomain.id().value(),
            openPaaSUser.id().value(), configurationValue);

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block();

        assertThat(actual)
            .contains(ConfigurationEntry.of("linagora.esn.unifiedinbox", "useEmailLinks", BooleanNode.TRUE),
                ConfigurationEntry.of("core", "language", new TextNode("vi")),
                ConfigurationEntry.of("core", "homePage", new TextNode("contact")),
                ConfigurationEntry.of("core", "datetime", toJsonNode("""
                    {
                        "timeZone": "America/Adak",
                        "use24hourFormat": true
                    }""")),
                ConfigurationEntry.of("core", "businessHours", toJsonNode("""
                    [
                        {
                            "daysOfWeek": [1,2,3,4,5,6],
                            "start": "9:0",
                            "end": "17:0"
                        }
                    ]""")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        """
        {
            "url": "amqp://guest:guest@rabbitmq:5672"
        }""",
        """
        {
            "backend": {
                "url": "http://esn_sabre:80"
            }
        }""",
        """
        {
            "application-menu:jobqueue": false,
            "application-menu:invitation": false,
            "control-center:password": true,
            "control-center:invitation": false,
            "header:user-notification": true
        }""",
        "\"unifiedinbox\"",
        """
        {
            "transport": {
                "module": "nodemailer-browser",
                "type": "MailBrowser",
                "config": {
                    "dir": "/tmp",
                    "browser": true
                }
            }
        }""",
        """
        {
            "maxSizeUpload": 104857600
        }""",
        "{}"
    })
    void retrieveShouldReturnCorrectConfigurationJsonNode(String configurationKeyValue) {
        String configurationValue = """
            [
                {
                    "name": "module_name1",
                    "configurations": [
                        { "name": "configuration_key1", "value": %s }
                    ]
                }
            ]""".formatted(configurationKeyValue);

        insertUserConfiguration(mongo.getDb(), openPaaSDomain.id().value(),
            openPaaSUser.id().value(), configurationValue);

        List<JsonNode> actual = testee().retrieveConfiguration(MAILBOX_SESSION)
            .map(ConfigurationEntry::node)
            .collectList().block();

        assertThat(actual)
            .contains(toJsonNode(configurationKeyValue));
    }

    @Test
    void retrieveShouldReturnEmptyWhenEmptyConfiguration() {
        insertUserConfiguration(mongo.getDb(), openPaaSDomain.id().value(),
            openPaaSUser.id().value(), "[]");

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block();

        assertThat(actual).isEmpty();
    }

    @Test
    void retrieveShouldReturnSingleConfigurationEntry() {
        String configurationValue = """
            [
                {
                    "name": "linagora.esn.unifiedinbox",
                    "configurations": [
                        { "name": "useEmailLinks", "value": true }
                    ]
                }
            ]""";

        insertUserConfiguration(mongo.getDb(), openPaaSDomain.id().value(),
            openPaaSUser.id().value(), configurationValue);

        List<ConfigurationEntry> actual = testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block();

        assertThat(actual)
            .contains(ConfigurationEntry.of("linagora.esn.unifiedinbox", "useEmailLinks", BooleanNode.TRUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{\"name\": \"core\" }]",  // Missing configurations
        "[{\"configurations\": [{\"name\": \"language\", \"value\": \"en\"}]}]", // Missing name
        "[{\"name\": \"core\", \"configurations\": [{\"name\": \"language\"}]}]",  // Missing value for 'language'
    })
    void retrieveShouldThrowWhenInvalidConfiguration(String configurationModules) {
        insertUserConfiguration(mongo.getDb(), openPaaSDomain.id().value(),
            openPaaSUser.id().value(), configurationModules);

        assertThatCode(() -> testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block())
            .isInstanceOf(UserConfigurationDeserializeException.class);
    }

    @Test
    void retrieveShouldThrowWhenModulesValuesIsNotAJson() {
        Document insertDocument = new Document(ImmutableMap
            .of("domain_id", new ObjectId(openPaaSDomain.id().value()),
                "user_id", new ObjectId(openPaaSUser.id().value()),
                "modules", "not_json"));

        Mono.from(mongo.getDb().getCollection(MongoDBUserConfigurationDAO.COLLECTION)
                .insertOne(insertDocument))
            .block();

        assertThatCode(() -> testee().retrieveConfiguration(MAILBOX_SESSION)
            .collectList().block())
            .isInstanceOf(UserConfigurationDeserializeException.class);
    }

    @Nested
    class DocumentConverter {
        @Test
        void convertToDocumentShouldSuccess() {
            Set<ConfigurationEntry> configurationEntries = Set.of(
                ConfigurationEntry.of("linagora.esn.unifiedinbox", "booleanNode", BooleanNode.TRUE),
                ConfigurationEntry.of("linagora.esn.unifiedinbox", "nullNode", null),
                ConfigurationEntry.of("linagora.esn", "emptyStringNode", toJsonNode("{}")),
                ConfigurationEntry.of("core", "textNode", new TextNode("vi")),
                ConfigurationEntry.of("core", "numberNode", new LongNode(123456789)),
                ConfigurationEntry.of("core", "objectNode", toJsonNode("""
                {
                    "timeZone": "America/Adak",
                    "use24hourFormat": true
                }""")),
                ConfigurationEntry.of("core", "arrayNode", toJsonNode("""
                 [ { "daysOfWeek": [1,2,3,4,5,6], "start": "9:0", "end": "17:0" } ]
                """)));

            Document document = MongoDBUserConfigurationDAO.convertToDocument(configurationEntries);
            assertThatJson(document.toJson())
                .withOptions(IGNORING_ARRAY_ORDER)
                .inPath("modules")
                .isEqualTo("""
                [
                      {
                        configurations: [
                          {
                            name: "textNode",
                            value: "vi"
                          },
                          {
                            name: "numberNode",
                            value: 123456789
                          },
                          {
                            name: "objectNode",
                            value: {
                              timeZone: "America/Adak",
                              use24hourFormat: true
                            }
                          },
                          {
                            name: "arrayNode",
                            value: [
                              {
                                daysOfWeek: [ 1, 2, 3, 4, 5, 6 ],
                                end: "17:0",
                                start: "9:0"
                              }
                            ]
                          }
                        ],
                        name: "core"
                      },
                      {
                        configurations: [ { name: "emptyStringNode", value: {} } ],
                        name: "linagora.esn"
                      },
                      {
                        configurations: [
                          {
                            name: "nullNode",
                            value: null
                          },
                          {
                            name: "booleanNode",
                            value: true
                          }
                        ],
                        name: "linagora.esn.unifiedinbox"
                      }
                    ]""");
        }

        @Test
        void toConfigurationEntryShouldReturnCorrectConfigurationEntry() {
            Set<ConfigurationEntry> originalEntries = Set.of(
                ConfigurationEntry.of("linagora.esn.unifiedinbox", "booleanNode", BooleanNode.TRUE),
                ConfigurationEntry.of("linagora.esn.unifiedinbox", "nullNode", null),
                ConfigurationEntry.of("linagora.esn", "emptyStringNode", toJsonNode("{}")),
                ConfigurationEntry.of("core", "textNode", new TextNode("vi")),
                ConfigurationEntry.of("core", "numberNode", new LongNode(123456789)),
                ConfigurationEntry.of("core", "objectNode", toJsonNode("""
                {
                    "timeZone": "America/Adak",
                    "use24hourFormat": true
                }""")),
                ConfigurationEntry.of("core", "arrayNode", toJsonNode("""
                 [ { "daysOfWeek": [1,2,3,4,5,6], "start": "9:0", "end": "17:0" } ]
                """)));

            Document document = MongoDBUserConfigurationDAO.convertToDocument(originalEntries);

            List<ConfigurationEntry> configurationEntry = MongoDBUserConfigurationDAO.toConfigurationEntry(document);
            assertThat(configurationEntry)
                .usingElementComparator(Comparator.comparing(Object::toString))
                .containsExactlyInAnyOrderElementsOf(originalEntries);
        }
    }

    private void insertUserConfiguration(MongoDatabase database,
                                         String domainId,
                                         String userId,
                                         String modulesValue) {
        List<Document> modules = Document.parse("{\"modules\":" + modulesValue + "}").getList("modules", Document.class);

        Document insertDocument = new Document(ImmutableMap
            .of("domain_id", new ObjectId(domainId),
                "user_id", new ObjectId(userId),
                "modules", modules));

        Mono.from(database.getCollection(MongoDBUserConfigurationDAO.COLLECTION)
                .insertOne(insertDocument))
            .block();
    }
}
