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

import jakarta.inject.Inject;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.james.core.Username;
import org.apache.james.user.ldap.LdapRepositoryConfiguration;

import com.linagora.calendar.saas.drive.api.AttachmentStorageException;
import com.linagora.calendar.saas.drive.api.AttachmentStorageException.Reason;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchScope;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class LdapTDriveInstanceResolver implements TDriveInstanceResolver {
    private static final String FQDN_ATTRIBUTE = "twakeWorkspaceUrl";
    private static final String EMAIL_ATTRIBUTE = "mail";
    private static final String OBJECT_CLASS_ATTRIBUTE = "objectClass";
    private static final int LOOKUP_RESULT_LIMIT = 2;

    private final LdapRepositoryConfiguration configuration;
    private final LDAPConnectionPool connectionPool;

    @Inject
    public LdapTDriveInstanceResolver(LdapRepositoryConfiguration configuration, LDAPConnectionPool connectionPool) {
        this.configuration = configuration;
        this.connectionPool = connectionPool;
    }

    @Override
    public Mono<String> resolve(Username username) {
        return Mono.fromCallable(() -> {
            if (username.getDomainPart().isEmpty()) {
                throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "LDAP Drive lookup requires an email address");
            }
            return resolvedHostname(connectionPool.search(searchRequest(username)));
        }).subscribeOn(Schedulers.boundedElastic())
            .onErrorMap(LDAPException.class, error -> new AttachmentStorageException(
                error.getResultCode().equals(ResultCode.SIZE_LIMIT_EXCEEDED) ? Reason.INVALID_ATTACHMENT : Reason.UPSTREAM_FAILURE,
                "LDAP Drive instance lookup failed", error));
    }

    private SearchRequest searchRequest(Username username) {
        Filter filter = Filter.createANDFilter(
            Filter.createEqualityFilter(OBJECT_CLASS_ATTRIBUTE, configuration.getUserObjectClass()),
            Filter.createEqualityFilter(EMAIL_ATTRIBUTE, username.asString()));
        // Request the operational attribute explicitly and bound ambiguous results.
        SearchRequest request = new SearchRequest(configuration.getUserBase(), SearchScope.SUB, filter, FQDN_ATTRIBUTE);
        request.setSizeLimit(LOOKUP_RESULT_LIMIT);
        return request;
    }

    private static String resolvedHostname(SearchResult result) {
        if (result.getEntryCount() != 1) {
            throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "Expected exactly one LDAP user for Drive lookup");
        }
        String[] values = result.getSearchEntries().getFirst().getAttributeValues(FQDN_ATTRIBUTE);
        if (ArrayUtils.getLength(values) != 1 || values[0].isBlank()) {
            throw new AttachmentStorageException(Reason.INVALID_ATTACHMENT, "LDAP user must have exactly one Drive hostname");
        }
        return values[0];
    }
}
