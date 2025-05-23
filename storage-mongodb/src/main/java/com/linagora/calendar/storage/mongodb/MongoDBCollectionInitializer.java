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

import jakarta.inject.Inject;

import org.apache.james.lifecycle.api.Startable;

import com.mongodb.reactivestreams.client.MongoDatabase;

public class MongoDBCollectionInitializer implements Startable {

    private final MongoDatabase mongoDatabase;

    @Inject
    public MongoDBCollectionInitializer(MongoDatabase mongoDatabase) {
        this.mongoDatabase = mongoDatabase;
    }

    public void start() {
        MongoDBCollectionFactory.initialize(mongoDatabase);
    }
}
