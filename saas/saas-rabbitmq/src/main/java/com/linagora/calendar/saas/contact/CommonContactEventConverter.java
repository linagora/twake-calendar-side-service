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
import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Action;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.AddressBookDeletedEvent;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.Audience.Domain;
import com.linagora.calendar.saas.contact.CommonContactOutboundEvent.ContactChangedEvent;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUserDAO;

import ezvcard.VCard;
import ezvcard.property.DateOrTimeProperty;
import ezvcard.property.FormattedName;
import ezvcard.property.RawProperty;
import it.cnr.iit.jscontact.tools.dto.Card;
import it.cnr.iit.jscontact.tools.dto.Name;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;
import it.cnr.iit.jscontact.tools.vcard.extensions.utils.VCardParser;
import reactor.core.publisher.Mono;

public class CommonContactEventConverter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final VCard2JSContact VCARD_TO_JSCONTACT = VCard2JSContact.builder()
        .config(VCard2JSContactConfig.builder().build())
        .build();

    private final AudienceResolver audienceResolver;

    @Inject
    public CommonContactEventConverter(OpenPaaSUserDAO userDAO, OpenPaaSDomainDAO domainDAO) {
        audienceResolver = new AudienceResolver(userDAO, domainDAO);
    }

    public Mono<ContactChangedEvent> convert(Action action, SabreContactNotificationDTO notification) {
        return audienceResolver.resolve(notification.owner())
            .defaultIfEmpty(new Audience.Unknown())
            .flatMap(audience -> Mono.fromCallable(() -> convertContact(action, notification, audience)));
    }

    public Mono<AddressBookDeletedEvent> convertAddressBookDeletion(SabreAddressBookDeletedNotificationDTO notification) {
        return audienceResolver.resolve(notification.owner())
            .defaultIfEmpty(new Audience.Unknown())
            .map(audience -> new AddressBookDeletedEvent(audience, URI.create(notification.path())));
    }

    public ContactChangedEvent convert(Action action, Audience audience, String path, String cardData, long revision, String etag) {
        try {
            ConvertedContact convertedContact = convertVCard(cardData);
            return ContactChangedEvent.builder()
                .audience(audience)
                .action(action)
                .path(URI.create(path))
                .uid(convertedContact.uid())
                .revision(revision)
                .etag(etag)
                .payload(convertedContact.payload())
                .build();
        } catch (CommonContactEventConversionException e) {
            throw e;
        } catch (Exception | LinkageError e) {
            // Reactor drops LinkageError as JVM fatal: the delivery would never be acked nor dead-lettered
            throw new CommonContactEventConversionException("Unable to convert contact at path " + path, e);
        }
    }

    private ContactChangedEvent convertContact(Action action, SabreContactNotificationDTO notification, Audience audience) {
        return convert(action, audience, notification.path(), notification.carddata(), notification.syncToken(), notification.etag());
    }

    private ConvertedContact convertVCard(String cardData) {
        try {
            VCard vCard = parseVCard(cardData);
            removeTextDates(vCard);
            ReadCard readCard = readVCard(vCard);
            Card card = readCard.card();
            restoreFormattedName(card, vCard);
            ContactUid uid = new ContactUid(Optional.ofNullable(card.getUid())
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new CommonContactEventConversionException("Missing required vCard UID in contact notification payload")));
            ObjectNode payload = OBJECT_MAPPER.readValue(Card.toJson(card), ObjectNode.class);
            UnreadableExtensionProperties.appendAsVCardProps(payload, readCard.unreadableProperties());
            return new ConvertedContact(uid, payload);
        } catch (CardException | IOException e) {
            throw new CommonContactEventConversionException("Unable to convert vCard to JSContact", e);
        }
    }

    // VCardParser registers the extended N / ADR scribes VCard2JSContact relies on to convert names and addresses
    private VCard parseVCard(String cardData) {
        return VCardParser.parse(cardData).stream()
            .findFirst()
            .orElseThrow(() -> new CommonContactEventConversionException("Unable to parse vCard in contact notification payload"));
    }

    // JSContact anniversaries require a date: jscontact-tools fails on free-text BDAY / ANNIVERSARY / DEATHDATE values
    private void removeTextDates(VCard vCard) {
        vCard.getProperties().stream()
            .filter(DateOrTimeProperty.class::isInstance)
            .map(DateOrTimeProperty.class::cast)
            .filter(property -> property.getText() != null)
            .toList()
            .forEach(vCard::removeProperty);
    }

    // The converter cannot read text parameters of most extension properties: these are carried over by hand instead
    private ReadCard readVCard(VCard vCard) throws CardException {
        try {
            return new ReadCard(VCARD_TO_JSCONTACT.convert(vCard).getFirst(), List.of());
        } catch (RuntimeException e) {
            if (!UnreadableExtensionProperties.isCauseOf(e)) {
                throw e;
            }
            List<RawProperty> unreadableProperties = UnreadableExtensionProperties.detach(vCard);
            return new ReadCard(VCARD_TO_JSCONTACT.convert(vCard).getFirst(), unreadableProperties);
        }
    }

    // jscontact-tools drops a FN equal to the UID, taking it for the placeholder it writes for nameless cards
    private void restoreFormattedName(Card card, VCard vCard) {
        if (hasFullName(card)) {
            return;
        }
        formattedName(vCard).ifPresent(fullName -> Optional.ofNullable(card.getName())
            .ifPresentOrElse(name -> name.setFull(fullName),
                () -> card.setName(Name.builder().full(fullName).build())));
    }

    private boolean hasFullName(Card card) {
        return Optional.ofNullable(card.getName())
            .map(Name::getFull)
            .filter(StringUtils::isNotBlank)
            .isPresent();
    }

    private Optional<String> formattedName(VCard vCard) {
        return Optional.ofNullable(vCard.getFormattedName())
            .map(FormattedName::getValue)
            .filter(StringUtils::isNotBlank);
    }

    private record ConvertedContact(ContactUid uid, ObjectNode payload) {
    }

    private record ReadCard(Card card, List<RawProperty> unreadableProperties) {
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

        Mono<CommonContactOutboundEvent.Audience> resolve(String principal) {
            return switch (principal) {
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
