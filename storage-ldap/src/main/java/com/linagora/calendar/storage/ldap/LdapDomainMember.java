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

import java.util.Optional;

import org.apache.james.core.MailAddress;

import com.google.common.base.Preconditions;

public record LdapDomainMember(Optional<String> uid,
                               String cn,
                               String sn,
                               Optional<String> givenName,
                               Optional<MailAddress> mail,
                               Optional<String> telephoneNumber,
                               Optional<String> displayName) {
    public static class Builder {
        private Optional<String> uid = Optional.empty();
        private String cn;
        private String sn;
        private Optional<String> givenName = Optional.empty();
        private Optional<MailAddress> mail = Optional.empty();
        private Optional<String> telephoneNumber = Optional.empty();
        private Optional<String> displayName = Optional.empty();

        public Builder uid(String uid) {
            this.uid = Optional.of(uid);
            return this;
        }

        public Builder cn(String cn) {
            this.cn = cn;
            return this;
        }

        public Builder sn(String sn) {
            this.sn = sn;
            return this;
        }

        public Builder givenName(String givenName) {
            this.givenName = Optional.of(givenName);
            return this;
        }

        public Builder mail(MailAddress mail) {
            this.mail = Optional.ofNullable(mail);
            return this;
        }

        public Builder telephoneNumber(String telephoneNumber) {
            this.telephoneNumber = Optional.of(telephoneNumber);
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = Optional.of(displayName);
            return this;
        }

        public LdapDomainMember build() {
            return new LdapDomainMember(uid, cn, sn, givenName, mail, telephoneNumber, displayName);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public LdapDomainMember {
        Preconditions.checkArgument(cn != null, "cn must not be null");
        Preconditions.checkArgument(sn != null, "sn must not be null");
    }
}
