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
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;
import com.linagora.calendar.dav.ContactUid;

import it.cnr.iit.jscontact.tools.dto.Card;
import it.cnr.iit.jscontact.tools.dto.EmailAddress;
import it.cnr.iit.jscontact.tools.dto.OnlineService;
import it.cnr.iit.jscontact.tools.dto.Phone;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.JSContact2VCardConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.jscontact2vcard.JSContact2VCard;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;

public class CollectedContactConverter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final JSContact2VCard JSCONTACT_TO_VCARD = JSContact2VCard.builder()
        // Validation requires a Java EL implementation, which jscontact-tools does not declare as a dependency.
        .config(JSContact2VCardConfig.builder().validateCard(false).build())
        .build();
    private static final VCard2JSContact VCARD_TO_JSCONTACT = VCard2JSContact.builder()
        .config(VCard2JSContactConfig.builder().build())
        .build();

    private final UidCalculator uidCalculator = new UidCalculator();

    public record ConvertedContact(ContactUid uid, byte[] vcard) {
    }

    public ConvertedContact convert(ObjectNode contactData) {
        try {
            Card card = Card.toJSCard(contactData.toString());
            ContactUid uid = uidCalculator.generateNormalizedUid(card);
            card.setUid(uid.value());
            return new ConvertedContact(uid, JSCONTACT_TO_VCARD.convertToText(card).getBytes(StandardCharsets.UTF_8));
        } catch (CollectedContactConversionException e) {
            throw e;
        } catch (CardException | RuntimeException | IOException e) {
            throw new CollectedContactConversionException("""
                Unable to convert collected JSContact to vCard:
                %s
                """.formatted(contactData.toPrettyString()), e);
        }
    }

    public ContactUid generateNormalizedUid(ObjectNode contactData) {
        try {
            return uidCalculator.generateNormalizedUid(Card.toJSCard(contactData.toString()));
        } catch (IOException | RuntimeException e) {
            throw new CollectedContactConversionException("Unable to generate collected JSContact UID", e);
        }
    }

    public Optional<ConvertedContact> convertForUpdate(byte[] existingVCard, ObjectNode incomingContactData) {
        try {
            Card existingCard = toJSContact(existingVCard);
            Card incomingCard = Card.toJSCard(incomingContactData.toString());
            ObjectNode existingContactData = toNormalizedObjectNode(existingCard);
            ObjectNode contactDataToConvert = shouldMergeComplementaryIdentities(existingCard, incomingCard)
                ? existingContactData.deepCopy().setAll(incomingContactData)
                : incomingContactData;
            ConvertedContact convertedUpdate = convert(contactDataToConvert);
            ObjectNode updatedContactData = toNormalizedObjectNode(toJSContact(convertedUpdate.vcard()));
            return existingContactData.equals(updatedContactData) ? Optional.empty() : Optional.of(convertedUpdate);
        } catch (CardException | IOException | RuntimeException e) {
            throw new CollectedContactConversionException("Unable to update collected contact from existing vCard", e);
        }
    }

    private boolean shouldMergeComplementaryIdentities(Card existingCard, Card incomingCard) {
        boolean existingHasEmail = uidCalculator.emailAddress(existingCard).isPresent();
        boolean existingHasMatrixId = uidCalculator.matrixId(existingCard).isPresent();
        boolean incomingHasEmail = uidCalculator.emailAddress(incomingCard).isPresent();
        boolean incomingHasMatrixId = uidCalculator.matrixId(incomingCard).isPresent();

        boolean shouldMergeMatrixIdIntoExistingEmail = existingHasEmail && incomingHasMatrixId && !incomingHasEmail;
        boolean shouldMergeEmailIntoExistingMatrixId = existingHasMatrixId && !existingHasEmail && incomingHasEmail && !incomingHasMatrixId;

        return shouldMergeMatrixIdIntoExistingEmail || shouldMergeEmailIntoExistingMatrixId;
    }

    private Card toJSContact(byte[] vCard) throws CardException {
        return VCARD_TO_JSCONTACT
            .convert(new String(vCard, StandardCharsets.UTF_8))
            .getFirst();
    }

    private ObjectNode toNormalizedObjectNode(Card card) throws IOException {
        ObjectNode contact = OBJECT_MAPPER.readValue(Card.toJson(card), ObjectNode.class);
        // VCard2JSContact keeps the source VERSION as a raw property. Reapplying it would override
        // the vCard 4.0 version generated by JSContact2VCard.
        Optional<ArrayNode> normalizedVCardProps = Optional.ofNullable(contact.get("vCardProps"))
            .filter(ArrayNode.class::isInstance)
            .map(ArrayNode.class::cast)
            .map(ArrayNode::deepCopy)
            .map(vCardProps -> vCardProps.removeIf(property -> property.isArray()
                && !property.isEmpty()
                && "version".equalsIgnoreCase(property.get(0).asText())));

        ObjectNode normalizedContact = contact.deepCopy().without("vCardProps");
        normalizedVCardProps
            .filter(vCardProps -> !vCardProps.isEmpty())
            .ifPresent(vCardProps -> normalizedContact.set("vCardProps", vCardProps));
        return normalizedContact;
    }

    static final class UidCalculator {
        private static final String MATRIX_SERVICE = "matrix";
        private static final String MATRIX_ID_PREFIX = "@";
        private static final char MATRIX_ID_SEPARATOR = ':';
        private static final char EMAIL_SEPARATOR = '@';
        private static final int NO_PREFERENCE = Integer.MAX_VALUE;

        ContactUid generateNormalizedUid(Card card) {
            return Optional.ofNullable(card.getUid())
                .filter(StringUtils::isNotBlank)
                .map(ContactUid::new)
                .orElseGet(() -> generateUid(card));
        }

        private ContactUid generateUid(Card card) {
            return emailAddress(card)
                .or(() -> matrixId(card))
                .or(() -> phoneNumber(card))
                .map(value -> value.toLowerCase(Locale.US))
                .map(this::sha1)
                .map(ContactUid::new)
                .orElseThrow(() -> new CollectedContactConversionException("Cannot generate contact UID: missing email, Matrix ID and phone number"));
        }

        private Optional<String> emailAddress(Card card) {
            return Optional.ofNullable(card.getEmails())
                .stream()
                .flatMap(emails -> emails.values().stream())
                .filter(email -> StringUtils.isNotBlank(email.getAddress()))
                .min(Comparator
                    .comparingInt((EmailAddress email) -> preference(email.getPref()))
                    .thenComparing(EmailAddress::getAddress))
                .map(EmailAddress::getAddress);
        }

        private Optional<String> matrixId(Card card) {
            return Optional.ofNullable(card.getOnlineServices())
                .stream()
                .flatMap(onlineServices -> onlineServices.values().stream())
                .filter(onlineService -> MATRIX_SERVICE.equalsIgnoreCase(onlineService.getService()))
                .filter(onlineService -> StringUtils.isNotBlank(normalizedMatrixId(onlineService)))
                .min(Comparator
                    .comparingInt((OnlineService onlineService) -> preference(onlineService.getPref()))
                    .thenComparing(this::normalizedMatrixId, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(this::normalizedMatrixId);
        }

        private Optional<String> phoneNumber(Card card) {
            return Optional.ofNullable(card.getPhones())
                .stream()
                .flatMap(phones -> phones.values().stream())
                .filter(phone -> StringUtils.isNotBlank(phone.getNumber()))
                .min(Comparator
                    .comparingInt((Phone phone) -> preference(phone.getPref()))
                    .thenComparing(Phone::getNumber))
                .map(Phone::getNumber);
        }

        private String normalizedMatrixId(OnlineService onlineService) {
            return Optional.ofNullable(onlineService.getUser())
                .filter(StringUtils::isNotBlank)
                .filter(matrixId -> Strings.CS.startsWith(matrixId, MATRIX_ID_PREFIX))
                .map(matrixId -> Splitter.on(MATRIX_ID_SEPARATOR)
                    .limit(2)
                    .splitToList(Strings.CS.removeStart(matrixId, MATRIX_ID_PREFIX)))
                .filter(parts -> parts.size() == 2 && parts.stream().allMatch(StringUtils::isNotBlank))
                .map(parts -> StringUtils.join(parts, EMAIL_SEPARATOR))
                .orElse(null);
        }

        private int preference(Integer value) {
            return Optional.ofNullable(value).orElse(NO_PREFERENCE);
        }

        @SuppressWarnings("deprecation")
        private String sha1(String value) {
            return Hashing.sha1()
                .hashString(value, StandardCharsets.UTF_8)
                .toString();
        }

    }
}
