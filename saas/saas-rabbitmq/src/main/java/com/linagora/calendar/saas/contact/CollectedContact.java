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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Splitter;
import com.google.common.hash.Hashing;
import com.linagora.calendar.dav.ContactUid;

import ezvcard.Ezvcard;
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

final class CollectedContact {
    private static final String MATRIX_SERVICE = "matrix";
    private static final String MATRIX_ID_PREFIX = "@";
    private static final char MATRIX_ID_SEPARATOR = ':';
    private static final char EMAIL_SEPARATOR = '@';
    private static final int NO_PREFERENCE = Integer.MAX_VALUE;

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
            return parse(Card.toJSCard(contactData.toString()));
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
            return Ezvcard.write(JSCONTACT_TO_VCARD.convert(card))
                .prodId(false)
                .caretEncoding(true)
                .register(new ExtendedAddressScribe())
                .register(new ExtendedStructuredNameScribe())
                .go()
                .getBytes(StandardCharsets.UTF_8);
        } catch (CardException | RuntimeException e) {
            throw new CollectedContactException("Unable to convert collected JSContact to vCard", e);
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

    private static Card parseVCard(byte[] vCard) {
        try {
            return VCARD_TO_JSCONTACT
                .convert(new String(vCard, StandardCharsets.UTF_8))
                .getFirst();
        } catch (CardException | RuntimeException e) {
            throw new CollectedContactException("Unable to parse collected vCard", e);
        }
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
                .thenComparing(EmailAddress::getAddress))
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
                .thenComparing(CollectedContact::toNormalizedMatrixId, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(CollectedContact::toNormalizedMatrixId);
    }

    private static Optional<String> extractPreferredPhoneNumber(Card card) {
        return Optional.ofNullable(card.getPhones())
            .stream()
            .flatMap(phones -> phones.values().stream())
            .filter(phone -> StringUtils.isNotBlank(phone.getNumber()))
            .min(Comparator
                .comparingInt((Phone phone) -> preferenceOrLast(phone.getPref()))
                .thenComparing(Phone::getNumber))
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
            .map(value -> new ContactUid(sha1(value.toLowerCase(Locale.US))));
    }

    @SuppressWarnings("deprecation")
    private static String sha1(String value) {
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
