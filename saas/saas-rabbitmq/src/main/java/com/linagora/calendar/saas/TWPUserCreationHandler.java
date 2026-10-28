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

package com.linagora.calendar.saas;

import java.io.IOException;

import org.apache.james.core.Username;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSMessageHandler;

import reactor.core.publisher.Mono;

// Handles the Twake Workplace user.created events: the member is added to the domain member address book,
// calendar user or not. Only internalEmail is read, the other fields being absent for B2C accounts.
public class TWPUserCreationHandler implements SaaSMessageHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TWPUserCreationHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SaaSUserProvisioner userProvisioner;

    public TWPUserCreationHandler(SaaSUserProvisioner userProvisioner) {
        this.userProvisioner = userProvisioner;
    }

    @Override
    public Mono<Void> handleMessage(byte[] message) {
        return Mono.fromCallable(() -> parseUsername(message))
            .doOnNext(username -> LOGGER.info("Adding user {} to the domain member address book following a TWP creation event", username.asString()))
            .flatMap(userProvisioner::addToDomainAddressBook);
    }

    private static Username parseUsername(byte[] message) throws IOException {
        JsonNode internalEmail = OBJECT_MAPPER.readTree(message).required("internalEmail");
        if (!internalEmail.isTextual() || internalEmail.asText().isBlank()) {
            throw new IllegalArgumentException("'internalEmail' must be a non-blank string");
        }
        return Username.of(internalEmail.asText());
    }
}
