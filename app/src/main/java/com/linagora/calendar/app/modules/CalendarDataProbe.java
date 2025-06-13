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

package com.linagora.calendar.app.modules;

import static com.linagora.calendar.storage.eventsearch.EventSearchQuery.MAX_LIMIT;
import static org.awaitility.Durations.ONE_HUNDRED_MILLISECONDS;

import java.util.List;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.utils.GuiceProbe;
import org.apache.james.vacation.api.AccountId;
import org.awaitility.Awaitility;
import org.awaitility.Durations;
import org.awaitility.core.ConditionFactory;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.dav.CardDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.eventsearch.CalendarEvents;
import com.linagora.calendar.storage.eventsearch.CalendarSearchService;
import com.linagora.calendar.storage.eventsearch.EventFields;
import com.linagora.calendar.storage.eventsearch.EventSearchQuery;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

public class CalendarDataProbe implements GuiceProbe {
    private static final ConditionFactory CALMLY_AWAIT = Awaitility
        .with().pollInterval(ONE_HUNDRED_MILLISECONDS)
        .and().pollDelay(ONE_HUNDRED_MILLISECONDS)
        .await()
        .atMost(Durations.TEN_SECONDS);

    private final UsersRepository usersRepository;
    private final DomainList domainList;
    private final OpenPaaSUserDAO usersDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final UserConfigurationDAO userConfigurationDAO;
    private final UploadedFileDAO uploadedFileDAO;
    private final CalDavClient calDavClient;
    private final CardDavClient cardDavClient;
    private final CalendarSearchService calendarSearchService;

    @Inject
    public CalendarDataProbe(UsersRepository usersRepository,
                             DomainList domainList,
                             OpenPaaSUserDAO usersDAO,
                             OpenPaaSDomainDAO domainDAO,
                             UserConfigurationDAO userConfigurationDAO,
                             UploadedFileDAO uploadedFileDAO,
                             CalDavClient calDavClient,
                             CardDavClient cardDavClient,
                             CalendarSearchService calendarSearchService) {
        this.usersRepository = usersRepository;
        this.domainList = domainList;
        this.usersDAO = usersDAO;
        this.domainDAO = domainDAO;
        this.userConfigurationDAO = userConfigurationDAO;
        this.uploadedFileDAO = uploadedFileDAO;
        this.calDavClient = calDavClient;
        this.cardDavClient = cardDavClient;
        this.calendarSearchService = calendarSearchService;
    }

    public CalendarDataProbe addDomain(Domain domain) {
        try {
            domainList.addDomain(domain);
            return this;
        } catch (Exception e) {
            if (StringUtils.contains(e.getMessage(), "already exists")) {
                return this;
            }
            throw new RuntimeException(e);
        }
    }

    public OpenPaaSId addUser(Username username, String password) {
        try {
            usersRepository.addUser(username, password);
            return usersDAO.add(username).map(OpenPaaSUser::id).block();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public OpenPaaSId addUser(Username username, String password, String firstName, String lastName) {
        try {
            usersRepository.addUser(username, password);
            return usersDAO.add(username, firstName, lastName).map(OpenPaaSUser::id).block();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void addUserToRepository(Username username, String password) {
        try {
            usersRepository.addUser(username, password);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public OpenPaaSId domainId(Domain domain) {
        return domainDAO.retrieve(domain)
            .map(OpenPaaSDomain::id).block();
    }

    public OpenPaaSId userId(Username username) {
        return usersDAO.retrieve(username)
            .map(OpenPaaSUser::id).block();
    }

    public OpenPaaSUser getUser(Username username) {
        return usersDAO.retrieve(username).block();
    }

    public List<ConfigurationEntry> retrieveConfiguration(MailboxSession session) {
        return userConfigurationDAO.retrieveConfiguration(session).collectList().block();
    }

    public OpenPaaSId saveUploadedFile(Username username, Upload upload) {
        return uploadedFileDAO.saveFile(username, upload).block();
    }

    public UploadedFile getUploadedFile(Username username, OpenPaaSId id) {
        return uploadedFileDAO.getFile(username, id).block();
    }

    public List<UploadedFile> listUploadedFiles(Username username) {
        return uploadedFileDAO.listFiles(username).collectList().block();
    }

    public byte[] exportCalendarFromCalDav(CalendarURL calendarURL, MailboxSession mailboxSession) {
        return calDavClient.export(calendarURL, mailboxSession).block();
    }

    public byte[] exportContactFromCardDav(Username username, OpenPaaSId userId, String addressBook) {
        return cardDavClient.exportContact(username, userId, addressBook).block();
    }

    public void indexCalendar(Username username, CalendarEvents calendarEvents) {
        AccountId accountId = AccountId.fromUsername(username);
        calendarSearchService.index(accountId, calendarEvents).block();

        CALMLY_AWAIT.until(() -> !calendarSearchService.search(accountId,
                new EventSearchQuery(calendarEvents.events().iterator().next().summary(), Optional.empty(),
                    Optional.empty(), Optional.empty(),
                    MAX_LIMIT, 0))
            .collectList().block().isEmpty());
    }

    public List<EventFields> searchEvents(Username username, String query) {
        AccountId accountId = AccountId.fromUsername(username);
        return calendarSearchService.search(accountId, simpleQuery(query))
            .collectList().block();
    }

    private EventSearchQuery simpleQuery(String query) {
        return new EventSearchQuery(query, Optional.empty(),
            Optional.empty(), Optional.empty(),
            MAX_LIMIT, 0);
    }
}
