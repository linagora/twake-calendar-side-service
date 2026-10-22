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

package com.linagora.calendar.storage.ldap;

import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.apache.james.core.MailAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.linagora.calendar.storage.UserContactResolver.UserContact;
import com.linagora.calendar.storage.UserNameResolver.UserNames;
import com.unboundid.ldap.sdk.SearchResultEntry;

public record LdapUser(Optional<String> uid,
                       Optional<String> cn,
                       Optional<String> sn,
                       Optional<String> givenName,
                       Optional<MailAddress> mail,
                       Optional<String> telephoneNumber,
                       Optional<String> displayName,
                       ImmutableMap<String, String> extraProperties) {
    public static class Builder {
        private Optional<String> uid = Optional.empty();
        private Optional<String> cn = Optional.empty();
        private Optional<String> sn = Optional.empty();
        private Optional<String> givenName = Optional.empty();
        private Optional<MailAddress> mail = Optional.empty();
        private Optional<String> telephoneNumber = Optional.empty();
        private Optional<String> displayName = Optional.empty();
        private ImmutableMap<String, String> extraProperties = ImmutableMap.of();

        public Builder uid(String uid) {
            this.uid = Optional.of(uid);
            return this;
        }

        public Builder cn(String cn) {
            this.cn = Optional.of(cn);
            return this;
        }

        public Builder sn(String sn) {
            this.sn = Optional.of(sn);
            return this;
        }

        public Builder givenName(String givenName) {
            this.givenName = Optional.ofNullable(givenName);
            return this;
        }

        public Builder mail(MailAddress mail) {
            this.mail = Optional.ofNullable(mail);
            return this;
        }

        public Builder telephoneNumber(String telephoneNumber) {
            this.telephoneNumber = Optional.ofNullable(telephoneNumber);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Optional.ofNullable(displayName);
            return this;
        }

        public Builder extraProperties(ImmutableMap<String, String> extraProperties) {
            this.extraProperties = extraProperties;
            return this;
        }

        public LdapUser build() {
            return new LdapUser(uid, cn, sn, givenName, mail, telephoneNumber, displayName, extraProperties);
        }
    }

    public static final Logger LOGGER = LoggerFactory.getLogger(LdapUser.class);

    public static Builder builder() {
        return new Builder();
    }

    public LdapUser {
        Preconditions.checkArgument(cn != null, "cn must not be null");
        Preconditions.checkArgument(sn != null, "sn must not be null");
    }

    public static LdapUser fromLdapEntry(SearchResultEntry entry, LdapVCardPropertiesConfiguration vcardPropertiesConfiguration) {
        LdapUser.Builder builder = LdapUser.builder();
        Optional.ofNullable(entry.getAttributeValue("uid")).ifPresent(builder::uid);
        Optional.ofNullable(entry.getAttributeValue("cn")).ifPresent(builder::cn);
        Optional.ofNullable(entry.getAttributeValue("sn")).ifPresent(builder::sn);
        Optional.ofNullable(entry.getAttributeValue("givenName")).ifPresent(builder::givenName);
        Optional.ofNullable(entry.getAttributeValue("mail"))
            .flatMap(mail -> {
                try {
                    return Optional.of(new MailAddress(mail));
                } catch (Exception e) {
                    LOGGER.error("Error while processing LDAP entry: {}", entry.getDN(), e);
                    return Optional.empty();
                }
            }).ifPresent(builder::mail);
        Optional.ofNullable(entry.getAttributeValue("telephoneNumber")).ifPresent(builder::telephoneNumber);
        Optional.ofNullable(entry.getAttributeValue("displayName")).ifPresent(builder::displayName);
        builder.extraProperties(readExtraProperties(entry, vcardPropertiesConfiguration));
        return builder.build();
    }

    private static ImmutableMap<String, String> readExtraProperties(SearchResultEntry entry, LdapVCardPropertiesConfiguration vcardPropertiesConfiguration) {
        return vcardPropertiesConfiguration.vcardPropertyByLdapAttribute().entrySet().stream()
            .flatMap(mapping -> Optional.ofNullable(StringUtils.trimToNull(entry.getAttributeValue(mapping.getKey())))
                .map(value -> Map.entry(mapping.getValue(), value))
                .stream())
            .collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public UserNames toUserNames() {
        String lastname = sn.orElse("");
        String commonName = cn.orElse("");
        String firstname = lastname.isEmpty() ? commonName : commonName.replace(lastname, "").trim();
        return new UserNames(firstname, lastname);
    }

    public UserContact toUserContact() {
        return UserContact.builder()
            .names(Optional.of(toUserNames()))
            .familyName(sn)
            .givenName(resolveGivenName())
            .displayName(resolveDisplayName())
            .telephoneNumber(telephoneNumber)
            .extraProperties(extraProperties)
            .build();
    }

    private Optional<String> resolveDisplayName() {
        return displayName
            .or(this::cn)
            .or(() -> mail.map(MailAddress::asString));
    }

    private Optional<String> resolveGivenName() {
        return givenName
            .or(this::extractGivenNameFromCommonName);
    }

    private Optional<String> extractGivenNameFromCommonName() {
        return cn
            .map(commonName -> Strings.CS.remove(commonName, sn.orElse("")))
            .map(StringUtils::trimToNull);
    }
}
