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

package com.linagora.calendar.webadmin.service;

import java.util.List;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.ldap.LdapDomainMemberProvider;
import com.linagora.calendar.storage.ldap.LdapUser;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.ContactUpdateContext;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.UpdateResult;

import reactor.core.publisher.Mono;

public class LdapToDavDomainMembersSyncService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LdapToDavDomainMembersSyncService.class);

    private final LdapDomainMemberProvider ldapDomainMemberProvider;
    private final CardDavClient davClient;
    private final Function<OpenPaaSId, DavDomainMemberUpdateApplier> davDomainMemberUpdateApplierFactory;

    @Inject
    public LdapToDavDomainMembersSyncService(LdapDomainMemberProvider ldapDomainMemberProvider,
                                             CardDavClient davClient) {
        this.ldapDomainMemberProvider = ldapDomainMemberProvider;
        this.davClient = davClient;
        this.davDomainMemberUpdateApplierFactory = openPaaSId -> new DavDomainMemberUpdateApplier.Default(davClient, openPaaSId);
    }

    public Mono<UpdateResult> syncDomainMembers(OpenPaaSDomain openPaaSDomain, ContactUpdateContext contexts) {
        return Mono.zip(fetchLdapDomainMembers(openPaaSDomain), fetchDavDomainMembers(openPaaSDomain))
            .flatMap(tuple -> {
                List<LdapUser> ldapMembers = tuple.getT1();
                List<AddressBookContact> davContacts = tuple.getT2();
                DomainMemberUpdate domainMemberUpdate = DomainMemberUpdate.compute(ldapMembers, davContacts);
                return davDomainMemberUpdateApplierFactory.apply(openPaaSDomain.id()).apply(domainMemberUpdate, contexts);
            })
            .doOnSubscribe(sub -> LOGGER.info("Syncing domain: {}", openPaaSDomain.domain()));
    }

    private Mono<List<AddressBookContact>> fetchDavDomainMembers(OpenPaaSDomain openPaaSDomain) {
        return davClient.listContactDomainMembers(openPaaSDomain.id())
            .map(AddressBookContact::parse)
            .defaultIfEmpty(List.of())
            .doOnError(throwable -> LOGGER.error("Error fetching DAV domain members for domain: {}", openPaaSDomain.domain(), throwable));
    }

    private Mono<List<LdapUser>> fetchLdapDomainMembers(OpenPaaSDomain openPaaSDomain) {
        return ldapDomainMemberProvider.domainMembers(openPaaSDomain.domain())
            .collectList()
            .defaultIfEmpty(List.of())
            .doOnError(throwable -> LOGGER.error("Error fetching LDAP domain members for domain: {}", openPaaSDomain.domain(), throwable));
    }
}
