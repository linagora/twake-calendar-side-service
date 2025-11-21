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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linagora.tmail.james.jmap.ticket.TicketStore;
import com.linagora.tmail.james.jmap.ticket.TicketStoreContract;

public class MongoDBTicketDAOTest implements TicketStoreContract {

    @RegisterExtension
    static DockerMongoDBExtension mongo = new DockerMongoDBExtension(List.of(MongoDBTicketDAO.COLLECTION));

    private MongoDBTicketDAO mongoDBTicketDAO;

    @BeforeEach
    void setUp() {
        mongoDBTicketDAO = new MongoDBTicketDAO(mongo.getDb());
    }

    @Override
    public TicketStore testee() {
        return mongoDBTicketDAO;
    }
}
