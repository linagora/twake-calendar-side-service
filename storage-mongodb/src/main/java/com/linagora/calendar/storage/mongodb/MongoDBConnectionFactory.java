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

import org.apache.james.metrics.api.MetricFactory;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.reactivestreams.client.MongoClients;
import com.mongodb.reactivestreams.client.MongoDatabase;

public class MongoDBConnectionFactory {
    public static MongoDatabase instantiateDB(MongoDBConfiguration configuration,
                                              MetricFactory metricFactory) {
        ConnectionString connectionString = new ConnectionString(configuration.mongoURL());

        MongoClientSettings.Builder settingsBuilder = MongoClientSettings.builder()
            .applyConnectionString(connectionString)
            .addCommandListener(new MongoCommandMetricsListener(metricFactory));

        // Override credential to use the database name as authSource if credentials are present
        if (connectionString.getCredential() != null) {
            MongoCredential credential = MongoCredential.createCredential(
                connectionString.getCredential().getUserName(),
                configuration.database(),
                connectionString.getCredential().getPassword()
            );
            settingsBuilder.credential(credential);
        }

        return MongoClients.create(settingsBuilder.build()).getDatabase(configuration.database());
    }
}
