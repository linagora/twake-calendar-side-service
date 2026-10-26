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

package com.linagora.calendar.dav;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.linagora.calendar.storage.UserContactResolver.UserContact;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.parameter.TelephoneType;
import ezvcard.property.Email;
import ezvcard.property.FormattedName;
import ezvcard.property.RawProperty;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import ezvcard.property.Uid;
import ezvcard.util.TelUri;

public record AddressBookContact(Optional<String> uid,
                                 Optional<String> familyName,
                                 Optional<String> givenName,
                                 Optional<String> displayName,
                                 Optional<MailAddress> mail,
                                 Optional<String> telephoneNumber,
                                 ImmutableListMultimap<String, String> extraProperties) {

    private static final String EXTENDED_PROPERTY_PREFIX = "X-";

    public static class DavContactCardParseException extends RuntimeException {
        public DavContactCardParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class Builder {
        private Optional<String> uid = Optional.empty();
        private Optional<String> familyName = Optional.empty();
        private Optional<String> givenName = Optional.empty();
        private Optional<String> displayName = Optional.empty();
        private Optional<MailAddress> mail = Optional.empty();
        private Optional<String> telephoneNumber = Optional.empty();
        private ImmutableListMultimap<String, String> extraProperties = ImmutableListMultimap.of();

        public Builder uid(String uid) {
            this.uid = Optional.of(uid);
            return this;
        }

        public Builder uid(Optional<String> uid) {
            this.uid = uid;
            return this;
        }

        public Builder familyName(String familyName) {
            this.familyName = Optional.of(familyName);
            return this;
        }

        public Builder familyName(Optional<String> familyName) {
            this.familyName = familyName;
            return this;
        }

        public Builder givenName(String givenName) {
            this.givenName = Optional.of(givenName);
            return this;
        }

        public Builder givenName(Optional<String> givenName) {
            this.givenName = givenName;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Optional.of(displayName);
            return this;
        }

        public Builder displayName(Optional<String> displayName) {
            this.displayName = displayName;
            return this;
        }

        public Builder mail(String mail) {
            this.mail = Optional.of(Throwing.supplier(() -> new MailAddress(mail)).get());
            return this;
        }

        public Builder mail(MailAddress mail) {
            this.mail = Optional.of(mail);
            return this;
        }

        public Builder mail(Optional<MailAddress> mail) {
            this.mail = mail;
            return this;
        }

        public Builder telephoneNumber(String telephoneNumber) {
            this.telephoneNumber = Optional.of(telephoneNumber);
            return this;
        }

        public Builder telephoneNumber(Optional<String> telephoneNumber) {
            this.telephoneNumber = telephoneNumber;
            return this;
        }

        public Builder extraProperties(ImmutableListMultimap<String, String> extraProperties) {
            this.extraProperties = extraProperties;
            return this;
        }

        public Builder userContact(UserContact userContact) {
            return familyName(userContact.familyName())
                .givenName(userContact.givenName())
                .displayName(userContact.displayName())
                .telephoneNumber(userContact.telephoneNumber())
                .extraProperties(userContact.extraProperties());
        }

        public AddressBookContact build() {
            return new AddressBookContact(uid, familyName, givenName, displayName, mail, telephoneNumber, extraProperties);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static List<AddressBookContact> parse(byte[] vcardData) {
        Preconditions.checkNotNull(vcardData, "vCard data must not be null");

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(vcardData)) {
            return Ezvcard.parse(inputStream).all()
                .stream()
                .map(AddressBookContact::asAddressBookContact)
                .toList();
        } catch (Exception e) {
            throw new DavContactCardParseException("parse vcard error", e);
        }
    }

    public static Optional<String> computeUid(Optional<String> existingUid, Optional<MailAddress> mail) {
        return existingUid.or(() -> mail.map(m ->
            UUID.nameUUIDFromBytes(m.asString().getBytes(StandardCharsets.UTF_8)).toString()));
    }

    private static AddressBookContact asAddressBookContact(VCard vcard) {
        StructuredName name = vcard.getStructuredName();
        Optional<String> familyName = Optional.ofNullable(name)
            .map(StructuredName::getFamily)
            .filter(StringUtils::isNotEmpty);

        Optional<String> givenName = Optional.ofNullable(name)
            .map(StructuredName::getGiven)
            .filter(StringUtils::isNotEmpty);

        Optional<MailAddress> mail = vcard.getEmails().stream()
            .map(Email::getValue)
            .filter(StringUtils::isNotEmpty)
            .findFirst()
            .map(Throwing.function(MailAddress::new));

        Optional<String> displayName = Optional.ofNullable(vcard.getFormattedName())
            .map(FormattedName::getValue)
            .filter(StringUtils::isNotEmpty);

        Optional<String> telephone = vcard.getTelephoneNumbers()
            .stream()
            .findFirst()
            .map(tel -> StringUtils.defaultIfEmpty(tel.getText(),
                Optional.ofNullable(tel.getUri()).map(TelUri::getNumber).orElse(null)));

        Optional<String> uid = Optional.ofNullable(vcard.getUid())
            .map(Uid::getValue)
            .filter(StringUtils::isNotEmpty);

        return AddressBookContact.builder()
            .uid(uid)
            .familyName(familyName)
            .givenName(givenName)
            .displayName(displayName)
            .mail(mail)
            .telephoneNumber(telephone)
            .extraProperties(extraProperties(vcard))
            .build();
    }

    // An extended property may occur several times: every value is kept, in the card order.
    private static ImmutableListMultimap<String, String> extraProperties(VCard vcard) {
        return vcard.getExtendedProperties().stream()
            .filter(property -> Strings.CI.startsWith(property.getPropertyName(), EXTENDED_PROPERTY_PREFIX))
            .filter(property -> StringUtils.isNotEmpty(property.getValue()))
            .collect(ImmutableListMultimap.toImmutableListMultimap(
                property -> property.getPropertyName().toUpperCase(Locale.US),
                RawProperty::getValue));
    }

    /**
     * Whether both contacts carry the same details, regardless of their uid.
     */
    public boolean hasSameContentAs(AddressBookContact other) {
        return Objects.equals(familyName, other.familyName)
            && Objects.equals(givenName, other.givenName)
            && Objects.equals(displayName, other.displayName)
            && Objects.equals(mail, other.mail)
            && Objects.equals(telephoneNumber, other.telephoneNumber)
            && Objects.equals(extraProperties, other.extraProperties);
    }

    public String vcardUid() {
        return computeUid(this.uid, this.mail)
            .orElse(UUID.randomUUID().toString());
    }

    public VCard toVcard() {
        VCard vcard = new VCard();
        vcard.setUid(new Uid(vcardUid()));

        this.displayName()
            .map(StringUtils::trimToNull)
            .ifPresent(vcard::setFormattedName);

        Optional<String> familyName = this.familyName().map(StringUtils::trimToNull);
        Optional<String> givenName = this.givenName().map(StringUtils::trimToNull);
        if (familyName.isPresent() || givenName.isPresent()) {
            StructuredName name = new StructuredName();
            familyName.ifPresent(name::setFamily);
            givenName.ifPresent(name::setGiven);
            vcard.setStructuredName(name);
        }

        this.mail().map(MailAddress::asString)
            .ifPresent(email -> vcard.addEmail(new Email(email)));

        this.telephoneNumber()
            .map(StringUtils::trimToNull)
            .ifPresent(phone -> {
                Telephone tel = new Telephone(phone);
                tel.getTypes().add(TelephoneType.WORK);
                vcard.addTelephoneNumber(tel);
            });

        this.extraProperties()
            .forEach((name, value) -> Optional.ofNullable(StringUtils.trimToNull(value))
                .ifPresent(trimmedValue -> vcard.addExtendedProperty(name, trimmedValue)));

        vcard.setVersion(VCardVersion.V4_0);
        return vcard;
    }

    public String toVcardString() {
        return Ezvcard.write(toVcard())
            .prodId(false)
            .go();
    }

    public byte[] toVcardBytes() {
        return toVcardString().getBytes(StandardCharsets.UTF_8);
    }
}
