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
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;

import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;

import reactor.core.publisher.Mono;

public record LocalPartTDriveInstanceResolver(Optional<String> domainOverride) implements TDriveInstanceResolver {
    // The ldap-rest instanceSlug rule strips characters outside [a-z0-9-] and leading or trailing hyphens:
    // https://github.com/linagora/ldap-rest/blob/26e13f49397b27eb52b9b95920656f4f6397e4cd/src/lib/instanceProviders.ts#L15-L29
    // Several local parts then share one slug (john.doe, john_doe, johndoe), and this resolver cannot tell which
    // account owns the instance. Only local parts the rule leaves unchanged are accepted, so a slug maps to one address.
    private static final Pattern UNAMBIGUOUS_SLUG = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    @Override
    public Mono<String> resolve(Username username) {
        return Mono.fromSupplier(() -> slug(username) + "." + domain(username));
    }

    private String slug(Username username) {
        String localPart = username.getLocalPart().toLowerCase(Locale.ROOT);
        if (!UNAMBIGUOUS_SLUG.matcher(localPart).matches()) {
            throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT,
                "Local part does not map to a unique Drive instance");
        }
        return localPart;
    }

    private String domain(Username username) {
        return domainOverride.or(() -> username.getDomainPart().map(Domain::asString))
            .map(domain -> domain.toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new AttachmentStorageException(Reason.INVALID_ATTACHMENT,
                "Local-part Drive resolution requires an email domain or twp.drive.local-part.domain"));
    }
}
