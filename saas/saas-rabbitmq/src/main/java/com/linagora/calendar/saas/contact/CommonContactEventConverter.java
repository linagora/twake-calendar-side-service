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

package com.linagora.calendar.saas.contact;

import java.io.IOException;
import java.net.URI;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience.Domain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import it.cnr.iit.jscontact.tools.dto.Card;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;
import reactor.core.publisher.Mono;

public class CommonContactEventConverter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AudienceResolver audienceResolver;

    @Inject
    public CommonContactEventConverter(OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO) {
        audienceResolver = new AudienceResolver(userDAO, domainDAO);
    }

    public Mono<CommonContactOutboundEvent> convert(CommonContactNotificationConsumer.Queue queue, SabreContactNotificationDTO notification) {
        return audienceResolver.resolve(notification)
            .defaultIfEmpty(new Audience.Unknown())
            .flatMap(audience -> Mono.fromCallable(() -> convertContact(queue.action(), notification, audience)));
    }

    private CommonContactOutboundEvent convertContact(Action action, SabreContactNotificationDTO notification, Audience audience) {
        try {
            URI path = URI.create(notification.path());
            ConvertedContact convertedContact = convertVCard(notification.carddata());
            return new CommonContactOutboundEvent(audience, action, path, convertedContact.uid(), convertedContact.payload());
        } catch (CommonContactEventConversionException e) {
            throw e;
        } catch (Exception e) {
            throw new CommonContactEventConversionException("Unable to convert Sabre contact notification", e);
        }
    }

    private ConvertedContact convertVCard(String cardData) {
        try {
            Card card = VCard2JSContact.builder()
                .config(VCard2JSContactConfig.builder().build())
                .build()
                .convert(cardData)
                .getFirst();
            ContactUid uid = new ContactUid(Optional.ofNullable(card.getUid())
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new CommonContactEventConversionException("Missing required vCard UID in contact notification payload")));
            ObjectNode payload = OBJECT_MAPPER.readValue(Card.toJson(card), ObjectNode.class);
            return new ConvertedContact(uid, payload);
        } catch (CardException | IOException e) {
            throw new CommonContactEventConversionException("Unable to convert vCard to JSContact", e);
        }
    }

    private record ConvertedContact(ContactUid uid, ObjectNode payload) {
    }

    private static class AudienceResolver {
        private static final String PRINCIPALS_USERS_PREFIX = "principals/users/";
        private static final String PRINCIPALS_DOMAINS_PREFIX = "principals/domains/";

        private final OpenPaaSUserDAO userDAO;
        private final OpenPaaSDomainDAO domainDAO;

        AudienceResolver(OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO) {
            this.userDAO = userDAO;
            this.domainDAO = domainDAO;
        }

        Mono<CommonContactOutboundEvent.Audience> resolve(SabreContactNotificationDTO notification) {
            return switch (notification.owner()) {
                case String owner when Strings.CS.startsWith(owner, PRINCIPALS_USERS_PREFIX) -> resolveUser(owner);
                case String owner when Strings.CS.startsWith(owner, PRINCIPALS_DOMAINS_PREFIX) -> resolveDomain(owner);
                case null, default -> Mono.empty();
            };
        }

        private Mono<CommonContactOutboundEvent.Audience> resolveUser(String owner) {
            return principalId(owner)
                .flatMap(userDAO::retrieve)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new CommonContactEventConversionException("Cannot resolve user audience from owner: " + owner))))
                .map(openPaaSUser -> new Audience.User(openPaaSUser.username()));
        }

        private Mono<CommonContactOutboundEvent.Audience> resolveDomain(String owner) {
            return principalId(owner)
                .flatMap(domainDAO::retrieve)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new CommonContactEventConversionException("Cannot resolve domain audience from owner: " + owner))))
                .map(domain -> new Domain(domain.domain()));
        }

        private Mono<OpenPaaSId> principalId(String principal) {
            return Mono.fromCallable(() -> StringUtils.substringAfterLast(principal, '/'))
                .map(OpenPaaSId::new);
        }
    }
}
