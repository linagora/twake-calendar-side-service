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

package com.linagora.calendar.webadmin;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.util.List;

import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.json.DTOConverter;
import org.apache.james.server.task.json.dto.AdditionalInformationDTO;
import org.apache.james.server.task.json.dto.AdditionalInformationDTOModule;
import org.apache.james.task.Hostname;
import org.apache.james.task.MemoryTaskManager;
import org.apache.james.task.TaskExecutionDetails;
import org.apache.james.task.TaskManager;
import org.apache.james.webadmin.WebAdminServer;
import org.apache.james.webadmin.WebAdminUtils;
import org.apache.james.webadmin.routes.TasksRoutes;
import org.apache.james.webadmin.utils.JsonTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.linagora.calendar.storage.MemoryOpenPaaSUserDAO;
import com.linagora.calendar.storage.OpenPaaSUser;
import com.linagora.calendar.storage.OpenPaaSUserDAO;
import com.linagora.calendar.storage.ldap.LdapUser;
import com.linagora.calendar.storage.ldap.LdapUserDAO;
import com.linagora.calendar.webadmin.service.LdapUsersImportService;
import com.linagora.calendar.webadmin.task.LdapUsersImportTaskAdditionalInformationDTO;

import io.restassured.RestAssured;
import reactor.core.publisher.Mono;

public class LdapUsersImportTest {

    private WebAdminServer webAdminServer;
    private OpenPaaSUserDAO userDAO;
    private LdapUserDAO ldapUserDAO;

    @BeforeEach
    void setUp() {
        userDAO = Mockito.spy(new MemoryOpenPaaSUserDAO());
        ldapUserDAO = Mockito.mock(LdapUserDAO.class);
        LdapUsersImportService ldapUsersImportService = new LdapUsersImportService(userDAO, ldapUserDAO);
        TaskManager taskManager = new MemoryTaskManager(new Hostname("foo"));
        webAdminServer = WebAdminUtils.createWebAdminServer(new CalendarUserTaskRoutes(new JsonTransformer(),
                taskManager,
                ImmutableSet.of(new CalendarUserTaskRoutes.LdapUsersImportRequestToTask(ldapUsersImportService))),
            new TasksRoutes(taskManager,
                new JsonTransformer(),
                new DTOConverter<>(ImmutableSet.<AdditionalInformationDTOModule<? extends TaskExecutionDetails.AdditionalInformation, ? extends AdditionalInformationDTO>>builder()
                    .add(LdapUsersImportTaskAdditionalInformationDTO.module())
                    .build())))
            .start();

        RestAssured.requestSpecification = WebAdminUtils.buildRequestSpecification(webAdminServer)
            .setBasePath(CalendarUserRoutes.BASE_PATH + "/tasks")
            .build();
    }

    @AfterEach
    void tearDown() {
        webAdminServer.destroy();
    }

    @Test
    void shouldShowAllInformationInResponse() throws Exception {
        LdapUser ldapUser1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();
        Mockito.when(ldapUserDAO.getAllUsers()).thenReturn(ImmutableList.of(ldapUser1));

        String taskId = given()
            .queryParam("task", "importFromLDAP")
            .when()
            .post()
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("import-ldap-users"))
            .body("additionalInformation.processedUserCount", is(1))
            .body("additionalInformation.failedUserCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("import-ldap-users"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldImportAllUsers() throws Exception {
        LdapUser ldapUser1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();
        LdapUser ldapUser2 = LdapUser.builder()
            .uid("james-user2")
            .cn("James User2")
            .sn("User2")
            .mail(new MailAddress("james-user2@james.org"))
            .build();
        Mockito.when(ldapUserDAO.getAllUsers()).thenReturn(ImmutableList.of(ldapUser1, ldapUser2));

        String taskId = given()
            .queryParam("task", "importFromLDAP")
            .when()
            .post()
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("import-ldap-users"))
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.failedUserCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("import-ldap-users"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));

        List<OpenPaaSUser> actual = userDAO.list().collectList().block();
        assertThat(actual).hasSize(2);
        assertThat(actual).anySatisfy(openPaaSUser -> {
            assertThat(openPaaSUser.username().asString()).isEqualTo("james-user@james.org");
            assertThat(openPaaSUser.firstname()).isEqualTo("James");
            assertThat(openPaaSUser.lastname()).isEqualTo("User");
        }).anySatisfy(openPaaSUser -> {
            assertThat(openPaaSUser.username().asString()).isEqualTo("james-user2@james.org");
            assertThat(openPaaSUser.firstname()).isEqualTo("James");
            assertThat(openPaaSUser.lastname()).isEqualTo("User2");
        });
    }

    @Test
    void shouldIgnoreLdapUsersThatDoNotHaveMailAddress() throws Exception {
        LdapUser ldapUser1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();
        LdapUser noMailUser = LdapUser.builder()
            .uid("no-mail-user")
            .cn("no-mail-user")
            .sn("no-mail-user")
            .build();
        Mockito.when(ldapUserDAO.getAllUsers()).thenReturn(ImmutableList.of(ldapUser1, noMailUser));

        String taskId = given()
            .queryParam("task", "importFromLDAP")
            .when()
            .post()
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("import-ldap-users"))
            .body("additionalInformation.processedUserCount", is(1))
            .body("additionalInformation.failedUserCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("import-ldap-users"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldReportCompletedWhenUserHasAlreadyExist() throws Exception {
        LdapUser ldapUser1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();
        LdapUser ldapUser2 = LdapUser.builder()
            .uid("james-user2")
            .cn("James User2")
            .sn("User2")
            .mail(new MailAddress("james-user2@james.org"))
            .build();
        Mockito.when(ldapUserDAO.getAllUsers()).thenReturn(ImmutableList.of(ldapUser1, ldapUser2));

        userDAO.add(Username.fromMailAddress(ldapUser1.mail().get())).block();

        String taskId = given()
            .queryParam("task", "importFromLDAP")
            .when()
            .post()
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("completed"))
            .body("taskId", is(taskId))
            .body("type", is("import-ldap-users"))
            .body("additionalInformation.processedUserCount", is(2))
            .body("additionalInformation.failedUserCount", is(0))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("import-ldap-users"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }

    @Test
    void shouldReportFailedUserCount() throws Exception {
        LdapUser ldapUser1 = LdapUser.builder()
            .uid("james-user")
            .cn("James User")
            .sn("User")
            .givenName("James")
            .mail(new MailAddress("james-user@james.org"))
            .telephoneNumber("+33612345678")
            .displayName("James User")
            .build();
        LdapUser ldapUser2 = LdapUser.builder()
            .uid("james-user2")
            .cn("James User2")
            .sn("User2")
            .mail(new MailAddress("james-user2@james.org"))
            .build();
        Mockito.when(ldapUserDAO.getAllUsers()).thenReturn(ImmutableList.of(ldapUser1, ldapUser2));

        doAnswer(invocation -> {
            Username username = invocation.getArgument(0);
            if (username.asString().equals("james-user@james.org")) {
                return Mono.error(new RuntimeException("Simulated failure for james-user"));
            }
            return invocation.callRealMethod();
        }).when(userDAO).add(any(), any(), any());

        String taskId = given()
            .queryParam("task", "importFromLDAP")
            .when()
            .post()
            .jsonPath()
            .get("taskId");;

        given()
            .basePath(TasksRoutes.BASE)
        .when()
            .get(taskId + "/await")
        .then()
            .body("status", is("failed"))
            .body("taskId", is(taskId))
            .body("type", is("import-ldap-users"))
            .body("additionalInformation.processedUserCount", is(1))
            .body("additionalInformation.failedUserCount", is(1))
            .body("additionalInformation.timestamp", is(notNullValue()))
            .body("additionalInformation.type", is("import-ldap-users"))
            .body("startedDate", is(notNullValue()))
            .body("submitDate", is(notNullValue()));
    }
}
