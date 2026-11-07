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

package com.linagora.calendar.saas.drive.tdrive;

import java.util.Locale;

import org.apache.james.core.Username;

import reactor.core.publisher.Mono;

public record LocalPartTDriveInstanceResolver(String instanceDomain) implements TDriveInstanceResolver {
    @Override
    public Mono<String> resolve(Username username) {
        return Mono.fromSupplier(() -> {
            // Match the ldap-rest instanceSlug rule when provisioning uses the local part.
            // https://github.com/linagora/ldap-rest/blob/26e13f49397b27eb52b9b95920656f4f6397e4cd/src/lib/instanceProviders.ts#L15-L29
            String slug = username.getLocalPart().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("^-+|-+$", "");
            return slug + "." + instanceDomain.toLowerCase(Locale.ROOT);
        });
    }
}
