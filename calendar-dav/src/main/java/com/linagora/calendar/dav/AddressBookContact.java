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
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.MailAddress;

import com.github.fge.lambdas.Throwing;
import com.google.common.base.Preconditions;

import ezvcard.Ezvcard;
import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.parameter.TelephoneType;
import ezvcard.property.Email;
import ezvcard.property.FormattedName;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import ezvcard.property.Uid;
import ezvcard.util.TelUri;

public record AddressBookContact(Optional<String> uid,
                                 Optional<String> familyName,
                                 Optional<String> givenName,
                                 Optional<String> displayName,
                                 Optional<MailAddress> mail,
                                 Optional<String> telephoneNumber) {

    public static class DavContactCardParseException extends RuntimeException {
        public DavContactCardParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static List<AddressBookContact> parse(byte[] vcardData) {
        Preconditions.checkNotNull(vcardData, "vCard data must not be null");

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(vcardData)) {
            return Ezvcard.parse(inputStream).all()
                .stream()
                .map(AddressBookContact::toDavContactCard)
                .toList();
        } catch (Exception e) {
            throw new DavContactCardParseException("parse vcard error", e);
        }
    }

    public static Optional<String> computeUid(Optional<String> existingUid, Optional<MailAddress> mail) {
        return existingUid.or(() -> mail.map(m ->
            UUID.nameUUIDFromBytes(m.asString().getBytes(StandardCharsets.UTF_8)).toString()));
    }

    private static AddressBookContact toDavContactCard(VCard vcard) {
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

        return new AddressBookContact(uid, familyName, givenName, displayName, mail, telephone);
    }

    public String vcardUid() {
        return computeUid(this.uid, this.mail)
            .orElse(UUID.randomUUID().toString());
    }

    public VCard toVcard() {
        VCard vcard = new VCard();
        vcard.setUid(new Uid(vcardUid()));

        String formattedName = StringUtils.defaultIfBlank(this.displayName().orElse(null),
            this.mail().map(MailAddress::asString).orElse(null));
        vcard.setFormattedName(StringUtils.trimToNull(formattedName));

        StructuredName name = new StructuredName();
        this.familyName().ifPresent(fn -> name.setFamily(StringUtils.trimToNull(fn)));
        this.givenName().ifPresent(given -> name.setGiven(StringUtils.trimToNull(given)));
        vcard.setStructuredName(name);

        this.mail().map(MailAddress::asString)
            .ifPresent(email -> vcard.addEmail(new Email(email)));

        this.telephoneNumber()
            .map(StringUtils::trimToNull)
            .ifPresent(phone -> {
                Telephone tel = new Telephone(phone);
                tel.getTypes().add(TelephoneType.WORK);
                vcard.addTelephoneNumber(tel);
            });

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
