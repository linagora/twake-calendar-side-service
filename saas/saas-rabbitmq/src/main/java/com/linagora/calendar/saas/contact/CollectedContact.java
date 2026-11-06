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
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.hash.Hashing;
import com.linagora.calendar.dav.ContactUid;
import com.linagora.calendar.dav.importer.DavResourceName;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.property.RawProperty;
import ezvcard.property.Uid;
import it.cnr.iit.jscontact.tools.dto.Card;
import it.cnr.iit.jscontact.tools.dto.EmailAddress;
import it.cnr.iit.jscontact.tools.dto.OnlineService;
import it.cnr.iit.jscontact.tools.dto.Phone;
import it.cnr.iit.jscontact.tools.exceptions.CardException;
import it.cnr.iit.jscontact.tools.vcard.converters.config.JSContact2VCardConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.config.VCard2JSContactConfig;
import it.cnr.iit.jscontact.tools.vcard.converters.jscontact2vcard.JSContact2VCard;
import it.cnr.iit.jscontact.tools.vcard.converters.vcard2jscontact.VCard2JSContact;
import it.cnr.iit.jscontact.tools.vcard.extensions.io.scribe.ExtendedAddressScribe;
import it.cnr.iit.jscontact.tools.vcard.extensions.io.scribe.ExtendedStructuredNameScribe;
import it.cnr.iit.jscontact.tools.vcard.extensions.utils.VCardParser;

final class CollectedContact {
    private static final String MATRIX_SERVICE = "matrix";
    private static final String MATRIX_ID_PREFIX = "@";
    private static final char MATRIX_ID_SEPARATOR = ':';
    private static final char EMAIL_SEPARATOR = '@';
    private static final int NO_PREFERENCE = Integer.MAX_VALUE;
    private static final String PREF = "pref";
    private static final String JSPROP = "JSPROP";
    private static final String UID = "uid";
    private static final int MIN_PREF = 1;
    private static final int MAX_PREF = 100;

    private static final JSContact2VCard JSCONTACT_TO_VCARD = JSContact2VCard.builder()
        // Validation requires a Java EL implementation, which jscontact-tools does not declare as a dependency.
        .config(JSContact2VCardConfig.builder().validateCard(false).build())
        .build();
    private static final VCard2JSContact VCARD_TO_JSCONTACT = VCard2JSContact.builder()
        .config(VCard2JSContactConfig.builder().build())
        .build();

    private final Card card;
    private final ContactUid uid;
    private final boolean hasEmailAddress;
    private final boolean hasMatrixId;

    private CollectedContact(Card card,
                             ContactUid uid,
                             boolean hasEmailAddress,
                             boolean hasMatrixId) {
        this.card = card;
        this.uid = uid;
        this.hasEmailAddress = hasEmailAddress;
        this.hasMatrixId = hasMatrixId;
    }

    static CollectedContact parse(ObjectNode contactData) {
        try {
            return parse(Card.toJSCard(normalize(contactData).toString()));
        } catch (CollectedContactException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new CollectedContactException("Unable to parse collected JSContact", e);
        }
    }

    static CollectedContact parse(byte[] vCard) {
        return parse(parseVCard(vCard));
    }

    ContactUid uid() {
        return uid;
    }

    /**
     * Name of the CardDAV resource storing this contact: the UID when it is a safe path segment, its SHA-1 otherwise.
     * Deterministic so that the next message for the same UID finds the stored card again.
     */
    ContactUid resourceName() {
        return Optional.of(uid.value())
            .filter(DavResourceName::isSafe)
            .map(ContactUid::new)
            .orElseGet(() -> new ContactUid(sha1(uid.value())));
    }

    boolean hasEmailAddress() {
        return hasEmailAddress;
    }

    boolean hasMatrixId() {
        return hasMatrixId;
    }

    String toJson() throws IOException {
        return Card.toJson(card);
    }

    byte[] toVCardAsBytes() {
        try {
            return writeVCard(card);
        } catch (CardException | RuntimeException e) {
            throw new CollectedContactException("Unable to convert collected JSContact to vCard", e);
        }
    }

    private static byte[] writeVCard(Card card) throws CardException {
        return Ezvcard.write(JSCONTACT_TO_VCARD.convert(card))
            .prodId(false)
            .caretEncoding(true)
            .register(new ExtendedAddressScribe())
            .register(new ExtendedStructuredNameScribe())
            .go()
            .getBytes(StandardCharsets.UTF_8);
    }

    // An invalid JSContact is refused as a whole: only what is lost through the vCard gets dropped
    private static boolean isReadableJSContact(ObjectNode card) {
        try {
            Card.toJSCard(card.toString());
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean goesThroughAVCard(ObjectNode card) {
        try {
            parseVCard(writeVCard(Card.toJSCard(card.toString())));
            return true;
        } catch (CardException | IOException | RuntimeException e) {
            return false;
        }
    }

    private static CollectedContact parse(Card card) {
        Optional<String> preferredEmailAddress = extractPreferredEmailAddress(card);
        Optional<String> preferredMatrixId = extractPreferredMatrixId(card);
        Optional<String> preferredPhoneNumber = extractPreferredPhoneNumber(card);
        Optional<ContactUid> explicitUid = Optional.ofNullable(card.getUid())
            .filter(StringUtils::isNotBlank)
            .map(ContactUid::new);
        ContactUid uid = explicitUid
            .or(() -> generateUid(preferredEmailAddress, preferredMatrixId, preferredPhoneNumber))
            .orElseThrow(() -> new CollectedContactException("Cannot generate contact UID: missing email, Matrix ID and phone number"));
        Card cardWithUid = explicitUid.isPresent() ? card : copyWithUid(card, uid);
        return new CollectedContact(cardWithUid, uid, preferredEmailAddress.isPresent(), preferredMatrixId.isPresent());
    }

    private static ObjectNode normalize(ObjectNode contactData) {
        ObjectNode copy = contactData.deepCopy();
        dropUidAVCardCannotCarry(copy);
        clampPrefs(copy);
        VCardSafeIds.renameUnsafeIds(copy);
        VCardSafeProps.dropUnsafeProps(copy, CollectedContact::goesThroughAVCard);
        if (isReadableJSContact(copy)) {
            VCardSafeFields.dropUnsafeFields(copy, CollectedContact::goesThroughAVCard);
        }
        return copy;
    }

    /**
     * A vCard turns a carriage return into a line feed, and UTF-8 has no form for an unpaired surrogate: the stored UID,
     * hence the resource name, would differ from the one of the message. A UID holding a control character is no real
     * identifier either: the card gets the UID generated from its email, Matrix id or phone instead. A card stored under
     * the former UID stays readable, but is not found by the next message: a second card is created.
     */
    private static void dropUidAVCardCannotCarry(ObjectNode contactData) {
        JsonNode uid = contactData.path(UID);
        if (uid.isTextual() && !isWritableInAVCard(uid.asText())) {
            contactData.remove(UID);
        }
    }

    private static boolean isWritableInAVCard(String uid) {
        return uid.chars().noneMatch(Character::isISOControl) && StandardCharsets.UTF_8.newEncoder().canEncode(uid);
    }

    // vCard readers reject PREF outside 1..100 (RFC 6350), so clamp it to keep stored cards readable back.

    private static void clampPrefs(JsonNode node) {
        if (node instanceof ObjectNode objectNode && objectNode.path(PREF).isIntegralNumber()) {
            objectNode.put(PREF, Math.clamp(objectNode.get(PREF).asLong(), MIN_PREF, MAX_PREF));
        }
        StreamSupport.stream(node.spliterator(), false)
            .forEach(CollectedContact::clampPrefs);
    }

    private static Card parseVCard(byte[] vCard) {
        try {
            return readVCard(VCardParser.parse(new String(vCard, StandardCharsets.UTF_8)));
        } catch (CardException | RuntimeException e) {
            throw new CollectedContactException("Unable to parse collected vCard", e);
        }
    }

    private static Card readVCard(List<VCard> vCards) throws CardException {
        try {
            return VCARD_TO_JSCONTACT.convert(vCards.toArray(VCard[]::new)).getFirst();
        } catch (CardException e) {
            if (!isUnreadableJSProp(e)) {
                throw e;
            }
            // Written from a field holding only what JSContact does not define: it has nothing to attach to on reading
            vCards.forEach(vCard -> vCard.removeExtendedProperty(JSPROP));
            return VCARD_TO_JSCONTACT.convert(vCards.toArray(VCard[]::new)).getFirst();
        } catch (RuntimeException e) {
            if (!isUnreadableExtensionParameter(e)) {
                throw e;
            }
            vCards.forEach(CollectedContact::dropUnreadableExtensionParameters);
            return VCARD_TO_JSCONTACT.convert(vCards.toArray(VCard[]::new)).getFirst();
        }
    }

    /**
     * jscontact-tools 2.1.0 reads every parameter of an extension property as a number, and throws on any other value
     * (VCardUtils#getVCardPropParams): such a card could never be read back. These parameters are dropped rather than the
     * contact, so that cards already stored with one can be read too. Properties it reads otherwise, that it names in an
     * internal list (SOCIALPROFILE, X-ABLABEL...), do have text parameters: only the ones it cannot read are concerned.
     */
    private static void dropUnreadableExtensionParameters(VCard vCard) {
        vCard.getExtendedProperties().stream()
            .filter(CollectedContact::hasUnreadableParameters)
            .forEach(property -> property.getParameters().clear());
    }

    private static boolean hasUnreadableParameters(RawProperty property) {
        VCard probe = new VCard(VCardVersion.V4_0);
        probe.setUid(new Uid("probe"));
        probe.setFormattedName("probe");
        probe.addProperty(property);
        try {
            VCARD_TO_JSCONTACT.convert(probe);
            return false;
        } catch (CardException | RuntimeException e) {
            return isUnreadableExtensionParameter(e);
        }
    }

    private static boolean isUnreadableJSProp(CardException e) {
        return Strings.CS.contains(e.getMessage(), JSPROP);
    }

    private static boolean isUnreadableExtensionParameter(Exception e) {
        return Throwables.getRootCause(e) instanceof NumberFormatException;
    }

    private static Card copyWithUid(Card card, ContactUid uid) {
        try {
            Card copiedCard = Card.toJSCard(Card.toJson(card));
            copiedCard.setUid(uid.value());
            return copiedCard;
        } catch (IOException e) {
            throw new CollectedContactException("Unable to copy collected JSContact with generated UID", e);
        }
    }

    private static Optional<String> extractPreferredEmailAddress(Card card) {
        return Optional.ofNullable(card.getEmails())
            .stream()
            .flatMap(emails -> emails.values().stream())
            .filter(email -> StringUtils.isNotBlank(email.getAddress()))
            .min(Comparator
                .comparingInt((EmailAddress email) -> preferenceOrLast(email.getPref()))
                .thenComparing(email -> toLowerCase(email.getAddress())))
            .map(EmailAddress::getAddress);
    }

    private static Optional<String> extractPreferredMatrixId(Card card) {
        return Optional.ofNullable(card.getOnlineServices())
            .stream()
            .flatMap(onlineServices -> onlineServices.values().stream())
            .filter(onlineService -> MATRIX_SERVICE.equalsIgnoreCase(onlineService.getService()))
            .filter(onlineService -> StringUtils.isNotBlank(toNormalizedMatrixId(onlineService)))
            .min(Comparator
                .comparingInt((OnlineService onlineService) -> preferenceOrLast(onlineService.getPref()))
                .thenComparing(onlineService -> toLowerCase(toNormalizedMatrixId(onlineService)), Comparator.nullsLast(Comparator.naturalOrder())))
            .map(CollectedContact::toNormalizedMatrixId);
    }

    private static Optional<String> extractPreferredPhoneNumber(Card card) {
        return Optional.ofNullable(card.getPhones())
            .stream()
            .flatMap(phones -> phones.values().stream())
            .filter(phone -> StringUtils.isNotBlank(phone.getNumber()))
            .min(Comparator
                .comparingInt((Phone phone) -> preferenceOrLast(phone.getPref()))
                .thenComparing(phone -> toLowerCase(phone.getNumber())))
            .map(Phone::getNumber);
    }

    private static String toNormalizedMatrixId(OnlineService onlineService) {
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

    private static int preferenceOrLast(Integer preference) {
        return Optional.ofNullable(preference).orElse(NO_PREFERENCE);
    }

    private static Optional<ContactUid> generateUid(Optional<String> preferredEmailAddress,
                                                    Optional<String> preferredMatrixId,
                                                    Optional<String> preferredPhoneNumber) {
        return preferredEmailAddress
            .or(() -> preferredMatrixId)
            .or(() -> preferredPhoneNumber)
            .map(value -> new ContactUid(sha1(toLowerCase(value))));
    }

    // The UID hashes the lower-cased value, so the choice of that value must not depend on case either.
    private static String toLowerCase(String value) {
        return Optional.ofNullable(value)
            .map(nonNullValue -> nonNullValue.toLowerCase(Locale.US))
            .orElse(null);
    }

    @SuppressWarnings("deprecation")
    static String sha1(String value) {
        return Hashing.sha1()
            .hashString(value, StandardCharsets.UTF_8)
            .toString();
    }

    public static final class CollectedContactException extends RuntimeException {
        CollectedContactException(String message) {
            super(message);
        }

        CollectedContactException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
