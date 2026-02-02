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

import org.apache.james.core.Domain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.tmail.saas.rabbitmq.subscription.SaaSMessageHandler;

import reactor.core.publisher.Mono;

public class SaaSDomainSubscriptionHandler implements SaaSMessageHandler {
    public static final boolean MAIL_DNS_CONFIGURAION_NOT_VALIDATED = false;

    private static final Logger LOGGER = LoggerFactory.getLogger(SaaSDomainSubscriptionHandler.class);

    private final OpenPaaSDomainDAO domainDAO;

    public SaaSDomainSubscriptionHandler(OpenPaaSDomainDAO domainDAO) {
        this.domainDAO = domainDAO;
    }

    @Override
    public Mono<Void> handleMessage(byte[] message) {
        return Mono.fromCallable(() -> SaaSCalendarSubscriptionDeserializer.parseDomainMessage(message))
            .filter(DomainSubscriptionMessage::hasCalendarFeature)
            .filter(domainSubscriptionMessage -> domainSubscriptionMessage.mailDnsConfigurationValidated().orElse(MAIL_DNS_CONFIGURAION_NOT_VALIDATED))
            .flatMap(domainMessage -> createDomainIfNotExists(domainMessage.domainObject()))
            .then();
    }

    private Mono<Void> createDomainIfNotExists(Domain domain) {
        return domainDAO.retrieve(domain)
            .doOnNext(existingDomain -> LOGGER.debug("Domain {} already exists, skipping creation", domain.asString()))
            .switchIfEmpty(domainDAO.add(domain)
                .doOnSuccess(created -> LOGGER.info("Created domain {} from SaaS subscription", domain.asString())))
            .then();
    }
}
