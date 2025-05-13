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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.james.core.Domain;
import org.apache.james.core.Username;
import org.apache.james.domainlist.api.DomainList;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.user.api.UsersRepository;
import org.apache.james.utils.GuiceProbe;

import com.linagora.calendar.dav.CalDavClient;
import com.linagora.calendar.storage.CalendarURL;
import com.linagora.calendar.storage.OpenPaaSDomain;
import com.linagora.calendar.storage.OpenPaaSDomainDAO;
import com.linagora.calendar.storage.OpenPaaSId;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.UploadedFileDAO;
import com.linagora.calendar.storage.configuration.ConfigurationEntry;
import com.linagora.calendar.storage.configuration.UserConfigurationDAO;
import com.linagora.calendar.storage.model.Upload;
import com.linagora.calendar.storage.model.UploadedFile;

public class CalendarDataProbe implements GuiceProbe {
    private final UsersRepository usersRepository;
    private final DomainList domainList;
    private final OpenPaaSUserDAO usersDAO;
    private final OpenPaaSDomainDAO domainDAO;
    private final UserConfigurationDAO userConfigurationDAO;
    private final UploadedFileDAO uploadedFileDAO;
    private final CalDavClient calDavClient;

    @Inject
    public CalendarDataProbe(UsersRepository usersRepository,
                             DomainList domainList,
                             OpenPaaSUserDAO usersDAO,
                             OpenPaaSDomainDAO domainDAO,
                             UserConfigurationDAO userConfigurationDAO,
                             UploadedFileDAO uploadedFileDAO,
                             CalDavClient calDavClient) {
        this.usersRepository = usersRepository;
        this.domainList = domainList;
        this.usersDAO = usersDAO;
        this.domainDAO = domainDAO;
        this.userConfigurationDAO = userConfigurationDAO;
        this.uploadedFileDAO = uploadedFileDAO;
        this.calDavClient = calDavClient;
    }

    public CalendarDataProbe addDomain(Domain domain) {
        try {
            domainList.addDomain(domain);
            return this;
        } catch (Exception e) {
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
}
