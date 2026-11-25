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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.apache.james.core.Username;
import org.apache.james.util.ReactorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.linagora.calendar.saas.SaaSUserProvisioner;
import com.linagora.calendar.storage.DomainSettingsResolver;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UserSearchMode;
import com.linagora.calendar.webadmin.service.DavDomainMemberUpdateApplier.ContactUpdateContext;

import reactor.core.publisher.Mono;

/**
 * Aligns the {@code domain-members} address book of a domain, readable by every member, with its effective user search
 * mode: an {@code enabled} domain lists its registered users, a {@code limited} or {@code disabled} one lists nobody.
 * The mode is resolved when the alignment runs, so that the last change of the setting wins whatever the order in
 * which alignments run. See docs/decisions.md, issue #1646.
 */
public class DomainMembersUserSearchModeService {

    public static class PopulateContext {
        private final AtomicInteger populatedCount = new AtomicInteger(0);
        private final List<Username> failedUsers = Collections.synchronizedList(new ArrayList<>());

        public int populatedCount() {
            return populatedCount.get();
        }

        public ImmutableList<Username> failedUsers() {
            return ImmutableList.copyOf(failedUsers);
        }
    }

    public sealed interface Outcome permits Populated, Cleared {
        UserSearchMode userSearchMode();

        boolean hasFailures();
    }

    public record Populated(UserSearchMode userSearchMode, PopulateContext context) implements Outcome {
        @Override
        public boolean hasFailures() {
            return !context.failedUsers().isEmpty();
        }
    }

    public record Cleared(UserSearchMode userSearchMode, DavDomainMemberUpdateApplier.UpdateResult result) implements Outcome {
        @Override
        public boolean hasFailures() {
            return result.hasFailures();
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DomainMembersUserSearchModeService.class);

    private final DomainSettingsResolver domainSettingsResolver;
    private final OpenPaaSUserDAO userDAO;
    private final SaaSUserProvisioner userProvisioner;
    private final DavDomainMembersClearService clearService;

    @Inject
    public DomainMembersUserSearchModeService(DomainSettingsResolver domainSettingsResolver,
                                              OpenPaaSUserDAO userDAO,
                                              SaaSUserProvisioner userProvisioner,
                                              DavDomainMembersClearService clearService) {
        this.domainSettingsResolver = domainSettingsResolver;
        this.userDAO = userDAO;
        this.userProvisioner = userProvisioner;
        this.clearService = clearService;
    }

    public Mono<Outcome> align(OpenPaaSDomain domain, PopulateContext populateContext, ContactUpdateContext clearContext) {
        return domainSettingsResolver.resolveUserSearchMode(domain.domain())
            .flatMap(mode -> {
                if (mode == UserSearchMode.ENABLED) {
                    return populate(domain, populateContext).thenReturn((Outcome) new Populated(mode, populateContext));
                }
                return clearService.clearDomainMembers(domain, clearContext)
                    .map(result -> (Outcome) new Cleared(mode, result));
            });
    }

    // The provisioner writes a card only while the domain is still enabled, and updates the card the member already has.
    private Mono<Void> populate(OpenPaaSDomain domain, PopulateContext context) {
        return userDAO.listByDomain(domain.domain())
            .map(OpenPaaSUser::username)
            .flatMap(username -> userProvisioner.addToDomainAddressBook(username)
                .then(Mono.fromRunnable(context.populatedCount::incrementAndGet))
                .onErrorResume(error -> {
                    LOGGER.error("Failed to add user {} to the domain members address book of domain {}",
                        username.asString(), domain.domain().asString(), error);
                    context.failedUsers.add(username);
                    return Mono.empty();
                }), ReactorUtils.LOW_CONCURRENCY)
            .then()
            .doOnSubscribe(sub -> LOGGER.info("Populating the domain members address book of domain {}", domain.domain().asString()));
    }
}
