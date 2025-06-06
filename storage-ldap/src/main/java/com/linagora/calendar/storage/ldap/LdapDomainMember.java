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

import org.apache.james.core.MailAddress;

public record LdapDomainMember(String uid,
                               String cn,
                               String sn,
                               String givenName,
                               MailAddress mail,
                               String telephoneNumber,
                               String displayName) {
    public static class Builder {
        private String uid;
        private String cn;
        private String sn;
        private String givenName;
        private MailAddress mail;
        private String telephoneNumber;
        private String displayName;

        public Builder uid(String uid) {
            this.uid = uid;
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
            this.givenName = givenName;
            return this;
        }

        public Builder mail(MailAddress mail) {
            this.mail = mail;
            return this;
        }

        public Builder telephoneNumber(String telephoneNumber) {
            this.telephoneNumber = telephoneNumber;
            return this;
        }

        public Builder displayName(String displayName) {
            this.displayName = displayName;
            return this;
        }

        public LdapDomainMember build() {
            return new LdapDomainMember(uid, cn, sn, givenName, mail, telephoneNumber, displayName);
        }
    }

    public static Builder builder() {
        return new Builder();
    }
}
