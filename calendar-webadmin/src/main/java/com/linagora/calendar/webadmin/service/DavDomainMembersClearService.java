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
import java.util.Set;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linagora.calendar.dav.AddressBookContact;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.ContactUpdateContext;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.UpdateResult;

import reactor.core.publisher.Mono;

public class DavDomainMembersClearService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DavDomainMembersClearService.class);

    private final CardDavClient davClient;
    private final Function<OpenPaaSId, DavDomainMemberUpdateApplier> davDomainMemberUpdateApplierFactory;

    @Inject
    public DavDomainMembersClearService(CardDavClient davClient) {
        this.davClient = davClient;
        this.davDomainMemberUpdateApplierFactory = openPaaSId -> new DavDomainMemberUpdateApplier.Default(davClient, openPaaSId);
    }

    public Mono<UpdateResult> clearDomainMembers(OpenPaaSDomain openPaaSDomain, ContactUpdateContext context) {
        return fetchDavDomainMembers(openPaaSDomain)
            .flatMap(davContacts -> {
                DomainMemberUpdate domainMemberUpdate = new DomainMemberUpdate(Set.of(), Set.copyOf(davContacts), Set.of());
                return davDomainMemberUpdateApplierFactory.apply(openPaaSDomain.id()).apply(domainMemberUpdate, context);
            })
            .doOnSubscribe(sub -> LOGGER.info("Clearing domain members contacts for domain: {}", openPaaSDomain.domain()));
    }

    private Mono<List<AddressBookContact>> fetchDavDomainMembers(OpenPaaSDomain openPaaSDomain) {
        return davClient.listContactDomainMembers(openPaaSDomain.id())
            .map(AddressBookContact::parse)
            .defaultIfEmpty(List.of())
            .doOnError(throwable -> LOGGER.error("Error fetching DAV domain members for domain: {}", openPaaSDomain.domain(), throwable));
    }
}
