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

import com.linagora.tmail.saas.rabbitmq.subscription.SaaSMessageHandler;

import reactor.core.publisher.Mono;

public class SaaSUserSubscriptionHandler implements SaaSMessageHandler {
    private final SaaSUserProvisioner userProvisioner;

    public SaaSUserSubscriptionHandler(SaaSUserProvisioner userProvisioner) {
        this.userProvisioner = userProvisioner;
    }

    @Override
    public Mono<Void> handleMessage(byte[] message) {
        return Mono.fromCallable(() -> SaaSCalendarSubscriptionDeserializer.parseUserMessage(message))
            .filter(UserSubscriptionMessage::hasCalendarFeature)
            .flatMap(userMessage -> userProvisioner.provisionUser(userMessage.username()))
            .then();
    }
}
