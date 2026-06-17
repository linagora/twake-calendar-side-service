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

package com.linagora.calendar.amqp;

import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import com.linagora.calendar.dav.DavTestHelper;
import com.linagora.calendar.dav.DockerSabreDavSetup;
import com.linagora.calendar.dav.SabreDavExtension;
import com.linagora.calendar.storage.OpenPaaSUser;

public class SabreDavWithAsyncSchedulingExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback, ParameterResolver {
    private final SabreDavExtension sabreDavExtension;
    private final SabreAsyncSchedulingExtension asyncSchedulingExtension;

    public SabreDavWithAsyncSchedulingExtension() {
        this(SabreDavExtension.shared());
    }

    public SabreDavWithAsyncSchedulingExtension(SabreDavExtension sabreDavExtension) {
        this.sabreDavExtension = Objects.requireNonNull(sabreDavExtension);
        this.asyncSchedulingExtension = new SabreAsyncSchedulingExtension(sabreDavExtension);
    }

    @Override
    public void beforeAll(ExtensionContext extensionContext) throws Exception {
        sabreDavExtension.beforeAll(extensionContext);
        asyncSchedulingExtension.beforeAll(extensionContext);
    }

    @Override
    public void beforeEach(ExtensionContext extensionContext) throws Exception {
        asyncSchedulingExtension.beforeEach(extensionContext);
    }

    @Override
    public void afterEach(ExtensionContext extensionContext) throws Exception {
        try {
            asyncSchedulingExtension.afterEach(extensionContext);
        } finally {
            sabreDavExtension.afterEach(extensionContext);
        }
    }

    @Override
    public void afterAll(ExtensionContext extensionContext) throws Exception {
        try {
            asyncSchedulingExtension.afterAll(extensionContext);
        } finally {
            sabreDavExtension.afterAll(extensionContext);
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return sabreDavExtension.supportsParameter(parameterContext, extensionContext);
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) throws ParameterResolutionException {
        return sabreDavExtension.resolveParameter(parameterContext, extensionContext);
    }

    public OpenPaaSUser newTestUser() {
        return sabreDavExtension.newTestUser();
    }

    public OpenPaaSUser newTestUser(Optional<String> prefix) {
        return sabreDavExtension.newTestUser(prefix);
    }

    public DockerSabreDavSetup dockerSabreDavSetup() {
        return sabreDavExtension.dockerSabreDavSetup();
    }

    public void deleteRabbitMQQueues(String... queueNames) {
        sabreDavExtension.deleteRabbitMQQueues(queueNames);
    }

    public DavTestHelper davTestHelper() {
        return sabreDavExtension.davTestHelper();
    }
}
