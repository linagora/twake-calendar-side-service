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

package com.linagora.calendar.storage;

import java.util.Optional;

import org.apache.james.core.Username;
import org.junit.jupiter.api.BeforeEach;

import com.linagora.calendar.storage.configuration.OIDCTokenCacheConfiguration;
import com.linagora.calendar.storage.model.Token;

public class CaffeineOIDCTokenCacheTest extends OIDCTokenCacheContract {

    private CaffeineOIDCTokenCache testee;

    @BeforeEach
    void setUp() {
        testee = new CaffeineOIDCTokenCache(tokenInfoResolver, OIDCTokenCacheConfiguration.DEFAULT);
    }

    @Override
    public OIDCTokenCache testee() {
        return testee;
    }

    @Override
    public Optional<Username> getUsernameFromCache(Token token) {
        return testee.getUsernameFromCache(token);
    }
}
