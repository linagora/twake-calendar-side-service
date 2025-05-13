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

package com.linagora.calendar.app;

import java.net.MalformedURLException;
import java.net.URL;

import com.google.inject.AbstractModule;
import com.google.inject.Module;
import com.google.inject.name.Names;
import com.linagora.calendar.dav.DavModuleTestHelper;

public class AppTestHelper {

    public static final Module BY_PASS_MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            install(OIDC_BY_PASS_MODULE);
            install(DavModuleTestHelper.BY_PASS_MODULE);
        }
    };

    public static final Module OIDC_BY_PASS_MODULE = new AbstractModule() {
        @Override
        protected void configure() {
            bind(URL.class).annotatedWith(Names.named("userInfo"))
                .toProvider(() -> {
                    try {
                        return new URL("https://neven.to.be.called.com");
                    } catch (MalformedURLException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    };
}
