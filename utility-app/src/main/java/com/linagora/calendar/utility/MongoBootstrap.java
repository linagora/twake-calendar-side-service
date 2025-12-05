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

package com.linagora.calendar.utility;

import java.io.FileNotFoundException;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.james.utils.PropertiesProvider;

import com.linagora.calendar.storage.mongodb.MongoDBConfiguration;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class MongoBootstrap {
    private final MongoClient mongoClient;
    private final MongoDatabase mongoDatabase;

    public MongoBootstrap() throws ConfigurationException, FileNotFoundException {
        AppConfiguration appConfig = AppConfiguration.builder()
            .useWorkingDirectoryEnvProperty()
            .build();
        PropertiesProvider props = appConfig.providePropertiesProvider();

        MongoDBConfiguration mongoConfig = MongoDBConfiguration.parse(props.getConfiguration("configuration"));

        MongoClientSettings settings = MongoClientSettings.builder()
            .applyConnectionString(new ConnectionString(mongoConfig.mongoURL()))
            .build();

        this.mongoClient = MongoClients.create(settings);
        this.mongoDatabase = mongoClient.getDatabase(mongoConfig.database());
    }

    public MongoDatabase mongoDatabase() {
        return mongoDatabase;
    }

    public void close() {
        mongoClient.close();
    }
}